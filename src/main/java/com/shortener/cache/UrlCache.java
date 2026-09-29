package com.shortener.cache;

import java.time.Duration;
import java.util.Optional;

/** Cache-aside store for code → long URL. A cached miss is stored as {@link #NOT_FOUND}. */
public interface UrlCache {

    String NOT_FOUND = "\u0000404";

    Optional<String> get(String code);

    void put(String code, String longUrl, Duration ttl);

    void evict(String code);

    String type();
}
