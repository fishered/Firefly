package com.firefly.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Coordinates shard ownership so only one scheduler node can advance a shard at a time.
 */
public interface ShardManager {
    Optional<ShardLease> acquire(int shardId, String nodeId, Instant now, Duration leaseDuration);

    Optional<ShardLease> renew(int shardId, String nodeId, long fencingToken, Instant now, Duration leaseDuration);

    boolean release(int shardId, String nodeId, long fencingToken);

    Optional<ShardLease> findLease(int shardId);
}
