package com.firefly.cluster;

/** Cluster-wide immutable scheduler partitioning contract. */
public record SchedulerShardConfig(int shardCount) {
    public static final int DEFAULT_SHARD_COUNT = 32;

    public SchedulerShardConfig {
        if (shardCount < 1 || shardCount > 4096) {
            throw new IllegalArgumentException("scheduler shardCount must be between 1 and 4096");
        }
    }

    public static SchedulerShardConfig defaults() {
        return new SchedulerShardConfig(DEFAULT_SHARD_COUNT);
    }
}
