package com.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        int codeLength,
        List<Shard> shards,
        String mysqlUrl,
        int shardPoolSize,
        Cache cache,
        Events events,
        Clicks clicks) {

    public record Shard(String url, String username, String password) {
    }

    public record Cache(String redisUrl, long ttlSeconds, long negativeTtlSeconds, long localMaxSize) {
        public boolean redisEnabled() {
            return redisUrl != null && !redisUrl.isBlank();
        }
    }

    public record Events(String kafkaBootstrapServers, String kafkaUsername, String kafkaPassword,
                         String kafkaSaslMechanism, String topic, int partitions, int consumerConcurrency) {
        public boolean kafkaEnabled() {
            return kafkaBootstrapServers != null && !kafkaBootstrapServers.isBlank();
        }
    }

    public record Clicks(long flushIntervalMs) {
    }
}
