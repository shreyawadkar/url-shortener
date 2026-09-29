package com.shortener.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed cache. Redis failures are logged and treated as misses so an outage
 * degrades to database reads instead of failing redirects.
 */
public class RedisUrlCache implements UrlCache {

    private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);
    private static final String PREFIX = "url:";

    private final StringRedisTemplate redis;

    public RedisUrlCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String code) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(PREFIX + code));
        } catch (RuntimeException e) {
            log.warn("Redis GET failed for {}: {}", code, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String code, String longUrl, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + code, longUrl, ttl);
        } catch (RuntimeException e) {
            log.warn("Redis SET failed for {}: {}", code, e.getMessage());
        }
    }

    @Override
    public void evict(String code) {
        try {
            redis.delete(PREFIX + code);
        } catch (RuntimeException e) {
            log.warn("Redis DEL failed for {}: {}", code, e.getMessage());
        }
    }

    @Override
    public String type() {
        return "redis";
    }
}
