package com.example.multidb;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.multidb.config.MultiDbRedisConnectionFactory;
import com.example.multidb.customer.Example;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest(properties = {
        "example.redis.multidb.endpoints[0].name=primary",
        "example.redis.multidb.endpoints[0].uri=redis://localhost:16379",
        "example.redis.multidb.endpoints[0].weight=1.0",
        "example.redis.multidb.endpoints[1].name=secondary",
        "example.redis.multidb.endpoints[1].uri=redis://localhost:16380",
        "example.redis.multidb.endpoints[1].weight=0.5",
        "example.redis.multidb.initialization-policy=MAJORITY_AVAILABLE"
})
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TESTS", matches = "true")
class MultiDbCustomerCodeIntegrationTests {

    private final List<String> testKeys = new ArrayList<>();

    @Autowired
    private Example example;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    @Qualifier("redisConnectionFactory")
    private RedisConnectionFactory customerConnectionFactory;

    @Test
    void unchangedCustomerCodeUsesTheMultiDbBackedTemplate() throws Exception {
        String key = newKey("list");

        example.addLink(key, URI.create("https://redis.io/").toURL());

        assertThat(redisTemplate.getConnectionFactory())
                .isInstanceOf(MultiDbRedisConnectionFactory.class)
                .isNotSameAs(customerConnectionFactory);
        assertThat(redisTemplate.opsForList().range(key, 0, -1))
                .isEqualTo(List.of("https://redis.io/", "https://redis.io/"));
    }

    @Test
    void valueOperationsWriteAndTemplateReadsAndOverwrites() {
        String key = newKey("value");
        assertThat(example.getValue(key)).isNull();
        example.setValue(key, "first");
        assertThat(example.getValue(key)).isEqualTo("first");
        example.setValue(key, "updated");
        assertThat(example.getValue(key)).isEqualTo("updated");
    }

    @Test
    void setOperationsDoNotDuplicateMembers() {
        String key = newKey("set");
        assertThat(example.addSetMember(key, "redis")).isEqualTo(1L);
        assertThat(example.addSetMember(key, "redis")).isZero();
        assertThat(example.addSetMember(key, "spring")).isEqualTo(1L);
        assertThat(example.isSetMember(key, "redis")).isTrue();
        assertThat(example.isSetMember(key, "missing")).isFalse();
        assertThat(redisTemplate.opsForSet().members(key)).containsExactlyInAnyOrder("redis", "spring");
    }

    @Test
    void sortedSetOperationsUpdateScoresAndPreserveScoreOrder() {
        String key = newKey("zset");
        assertThat(example.getZSetScore(key, "alice")).isNull();
        assertThat(example.addZSetMember(key, "alice", 10)).isTrue();
        assertThat(example.addZSetMember(key, "bob", 20)).isTrue();
        assertThat(example.getZSetScore(key, "alice")).isEqualTo(10.0);
        assertThat(example.addZSetMember(key, "alice", 30)).isFalse();
        assertThat(example.getZSetScore(key, "alice")).isEqualTo(30.0);
        assertThat(redisTemplate.opsForZSet().range(key, 0, -1)).containsExactly("bob", "alice");
    }

    @Test
    void hashOperationsWriteAndUpdateIndividualFields() {
        String key = newKey("hash");
        assertThat(example.getHashField(key, "name")).isNull();
        example.putHashField(key, "name", "Alice");
        example.putHashField(key, "language", "ko");
        assertThat(example.getHashField(key, "name")).isEqualTo("Alice");
        example.putHashField(key, "name", "Bob");
        assertThat(example.getHashField(key, "name")).isEqualTo("Bob");
        assertThat(redisTemplate.<String, String>opsForHash().entries(key))
                .containsExactlyInAnyOrderEntriesOf(Map.of("name", "Bob", "language", "ko"));
    }

    private String newKey(String type) {
        String key = "integration:" + type + ":" + UUID.randomUUID();
        testKeys.add(key);
        return key;
    }

    @AfterEach
    void deleteOnlyThisTestsKeys() {
        if (!testKeys.isEmpty()) redisTemplate.delete(testKeys);
    }
}
