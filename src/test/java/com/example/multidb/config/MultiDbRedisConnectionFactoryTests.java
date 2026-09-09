package com.example.multidb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import io.lettuce.core.RedisURI;
import io.lettuce.core.failover.api.DatabaseConfig;
import io.lettuce.core.failover.api.InitializationPolicy;
import io.lettuce.core.failover.api.MultiDbOptions;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;

class MultiDbRedisConnectionFactoryTests {

    @Test
    void rejectsConnectionsBeforeLifecycleStart() {
        MultiDbRedisConnectionFactory factory = newFactory();

        assertThat(factory.isRunning()).isFalse();
        assertThatThrownBy(factory::getConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    void rejectsClusterAndSentinelModes() {
        MultiDbRedisConnectionFactory factory = newFactory();

        assertThatThrownBy(factory::getClusterConnection)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Cluster");
        assertThatThrownBy(factory::getSentinelConnection)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Sentinel");
    }

    @Test
    void pipelineAndTransactionAreFailFastContracts() throws Exception {
        assertThat(MultiDbRedisConnectionFactory.class.getDeclaredClasses())
                .extracting(Class::getSimpleName)
                .contains("MultiDbLettuceConnection", "UnsupportedDedicatedConnectionProvider");

        assertThat(RedisConnection.class.getMethod("openPipeline")).isNotNull();
        assertThat(RedisConnection.class.getMethod("multi")).isNotNull();
    }

    private static MultiDbRedisConnectionFactory newFactory() {
        List<DatabaseConfig> databases = List.of(
                DatabaseConfig.builder(RedisURI.create("redis://localhost:6379")).weight(1.0f).build(),
                DatabaseConfig.builder(RedisURI.create("redis://localhost:6380")).weight(0.5f).build());
        MultiDbOptions options = MultiDbOptions.builder()
                .initializationPolicy(InitializationPolicy.BuiltIn.ONE_AVAILABLE)
                .build();
        return new MultiDbRedisConnectionFactory(databases, options, Duration.ofSeconds(2));
    }
}
