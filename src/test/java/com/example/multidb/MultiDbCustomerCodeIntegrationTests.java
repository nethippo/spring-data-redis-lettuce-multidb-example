package com.example.multidb;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import com.example.multidb.config.MultiDbRedisConnectionFactory;
import com.example.multidb.customer.Example;
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

    @Autowired
    private Example example;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    @Qualifier("redisConnectionFactory")
    private RedisConnectionFactory customerConnectionFactory;

    @Test
    void unchangedCustomerCodeUsesTheMultiDbBackedTemplate() throws Exception {
        String key = "integration:user:42";
        redisTemplate.delete(key);

        example.addLink(key, URI.create("https://redis.io/").toURL());

        assertThat(redisTemplate.getConnectionFactory())
                .isInstanceOf(MultiDbRedisConnectionFactory.class)
                .isNotSameAs(customerConnectionFactory);
        assertThat(redisTemplate.opsForList().range(key, 0, -1))
                .isEqualTo(List.of("https://redis.io/", "https://redis.io/"));

        redisTemplate.delete(key);
    }
}
