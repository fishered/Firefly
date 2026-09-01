package com.firefly.batch;

import java.util.Map;

/** Immutable input slice assigned to one batch shard. */
public record BatchPartition(int shardIndex, int totalShards, String partitionKey,
                             long offset, long limit, Map<String, String> attributes) {
    public BatchPartition {
        if (shardIndex < 0 || totalShards < 1 || shardIndex >= totalShards) throw new IllegalArgumentException("invalid partition identity");
        if (partitionKey == null || partitionKey.isBlank()) throw new IllegalArgumentException("partitionKey must not be blank");
        if (offset < 0 || limit < 0) throw new IllegalArgumentException("offset and limit must not be negative");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
