package com.shortener.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;
import java.util.Optional;

public class LocalUrlCache implements UrlCache {

    private record Entry(String value, long ttlNanos) {
    }

    private final Cache<String, Entry> cache;

    public LocalUrlCache(long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<String, Entry>() {
                    @Override
                    public long expireAfterCreate(String key, Entry value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Entry value, long currentTime, long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Entry value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override
    public Optional<String> get(String code) {
        Entry e = cache.getIfPresent(code);
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    @Override
    public void put(String code, String longUrl, Duration ttl) {
        cache.put(code, new Entry(longUrl, ttl.toNanos()));
    }

    @Override
    public void evict(String code) {
        cache.invalidate(code);
    }

    @Override
    public String type() {
        return "local";
    }
}
