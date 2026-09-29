package com.shortener.shard;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Maps a short code to a shard. CRC32 is stable across JVMs and languages, so the
 * click-event Lambda and any other consumer route the same code to the same shard.
 */
public final class ShardRouter {

    private final int shardCount;

    public ShardRouter(int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        this.shardCount = shardCount;
    }

    public int shardFor(String code) {
        CRC32 crc = new CRC32();
        crc.update(code.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % shardCount);
    }

    public int shardCount() {
        return shardCount;
    }
}
