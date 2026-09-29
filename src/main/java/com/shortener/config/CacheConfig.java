package com.shortener.config;

import com.shortener.cache.LocalUrlCache;
import com.shortener.cache.RedisUrlCache;
import com.shortener.cache.UrlCache;
import io.lettuce.core.RedisURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean(destroyMethod = "")
    public UrlCache urlCache(AppProperties props) {
        AppProperties.Cache cfg = props.cache();
        if (!cfg.redisEnabled()) {
            log.info("REDIS_URL not set: using in-process Caffeine cache");
            return new LocalUrlCache(cfg.localMaxSize());
        }
        RedisURI uri = RedisURI.create(cfg.redisUrl());
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getUsername() != null) {
            standalone.setUsername(uri.getUsername());
        }
        if (uri.getPassword() != null) {
            standalone.setPassword(uri.getPassword());
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500));
        if (uri.isSsl()) {
            client.useSsl();
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client.build());
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        log.info("Using Redis cache at {}:{}", uri.getHost(), uri.getPort());
        return new RedisUrlCache(template);
    }
}
