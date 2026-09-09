package com.example.multidb.config;

import java.util.List;

import io.lettuce.core.RedisURI;
import io.lettuce.core.failover.api.DatabaseConfig;
import io.lettuce.core.failover.api.InitializationPolicy;
import io.lettuce.core.failover.api.MultiDbOptions;

import com.example.multidb.customer.Example;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class MultiDbRedisConfiguration {

    @Bean
    @Primary
    public MultiDbRedisConnectionFactory multiDbRedisConnectionFactory(MultiDbRedisProperties properties) {
        validate(properties);

        List<DatabaseConfig> databases = properties.getEndpoints().stream()
                .map(endpoint -> DatabaseConfig.builder(RedisURI.create(endpoint.getUri()))
                        .weight(endpoint.getWeight())
                        .build())
                .toList();

        MultiDbOptions options = MultiDbOptions.builder()
                .initializationPolicy(toLettucePolicy(properties.getInitializationPolicy()))
                .failbackSupported(properties.isFailbackEnabled())
                .failbackCheckInterval(properties.getFailbackCheckInterval())
                .gracePeriod(properties.getGracePeriod())
                .build();

        return new MultiDbRedisConnectionFactory(databases, options, properties.getCommandTimeout());
    }

    @Bean(name = "redisTemplate")
    public RedisTemplate<String, String> redisTemplate(
            MultiDbRedisConnectionFactory multiDbRedisConnectionFactory) {

        RedisTemplate<String, String> template = new RedisTemplate<>();
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setConnectionFactory(multiDbRedisConnectionFactory);
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.setEnableTransactionSupport(false);
        return template;
    }

    @Bean
    public Example example() {
        return new Example();
    }

    private static InitializationPolicy toLettucePolicy(
            MultiDbRedisProperties.InitializationPolicy policy) {

        return switch (policy) {
            case ONE_AVAILABLE -> InitializationPolicy.BuiltIn.ONE_AVAILABLE;
            case MAJORITY_AVAILABLE -> InitializationPolicy.BuiltIn.MAJORITY_AVAILABLE;
            case ALL_AVAILABLE -> InitializationPolicy.BuiltIn.ALL_AVAILABLE;
        };
    }

    private static void validate(MultiDbRedisProperties properties) {
        Assert.notNull(properties.getEndpoints(), "MultiDB endpoints must be configured");
        Assert.isTrue(properties.getEndpoints().size() >= 2,
                "At least two MultiDB endpoints must be configured");
        Assert.isTrue(properties.getCommandTimeout().isPositive(),
                "Command timeout must be positive");

        for (MultiDbRedisProperties.Endpoint endpoint : properties.getEndpoints()) {
            Assert.hasText(endpoint.getName(), "Each MultiDB endpoint must have a name");
            Assert.hasText(endpoint.getUri(), "Each MultiDB endpoint must have a URI");
            Assert.isTrue(endpoint.getWeight() > 0, "Each MultiDB endpoint weight must be positive");
        }
    }
}
