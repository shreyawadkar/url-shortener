package com.shortener.shard;

import java.time.Instant;

public record UrlRecord(String code, String longUrl, Instant createdAt, Instant expiresAt,
                        long clickCount, Instant lastAccessedAt) {

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
