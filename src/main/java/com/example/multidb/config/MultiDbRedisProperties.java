package com.example.multidb.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("example.redis.multidb")
public class MultiDbRedisProperties {

    private List<Endpoint> endpoints = new ArrayList<>();
    private Duration commandTimeout = Duration.ofSeconds(2);
    private InitializationPolicy initializationPolicy = InitializationPolicy.ONE_AVAILABLE;
    private boolean failbackEnabled = true;
    private Duration failbackCheckInterval = Duration.ofSeconds(30);
    private Duration gracePeriod = Duration.ofSeconds(60);

    public List<Endpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
    }

    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    public InitializationPolicy getInitializationPolicy() {
        return initializationPolicy;
    }

    public void setInitializationPolicy(InitializationPolicy initializationPolicy) {
        this.initializationPolicy = initializationPolicy;
    }

    public boolean isFailbackEnabled() {
        return failbackEnabled;
    }

    public void setFailbackEnabled(boolean failbackEnabled) {
        this.failbackEnabled = failbackEnabled;
    }

    public Duration getFailbackCheckInterval() {
        return failbackCheckInterval;
    }

    public void setFailbackCheckInterval(Duration failbackCheckInterval) {
        this.failbackCheckInterval = failbackCheckInterval;
    }

    public Duration getGracePeriod() {
        return gracePeriod;
    }

    public void setGracePeriod(Duration gracePeriod) {
        this.gracePeriod = gracePeriod;
    }

    public enum InitializationPolicy {
        ONE_AVAILABLE,
        MAJORITY_AVAILABLE,
        ALL_AVAILABLE
    }

    public static class Endpoint {

        private String name;
        private String uri;
        private float weight = 1.0f;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public float getWeight() {
            return weight;
        }

        public void setWeight(float weight) {
            this.weight = weight;
        }
    }
}
