package io.github.nishi.firefly.cluster;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Provides stable job-to-shard mapping independent of Java's randomized hash internals.
 */
public final class ShardHasher {
    private ShardHasher() {
    }

    public static int shardFor(String key, int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be greater than 0");
        }
        CRC32 crc32 = new CRC32();
        crc32.update(key.getBytes(StandardCharsets.UTF_8));
        return Math.floorMod((int) crc32.getValue(), shardCount);
    }
}
