package com.firefly.cluster;

import java.time.Instant;
import java.util.Objects;

/**
 * Grants one node the right to schedule one shard until the lease expires.
 */
public record ShardLease(
        int shardId,
        String ownerNodeId,
        Instant leaseUntil,
        long fencingToken
) {
    public ShardLease {
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must not be negative");
        }
        if (ownerNodeId.isBlank()) {
            throw new IllegalArgumentException("ownerNodeId must not be blank");
        }
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be greater than 0");
        }
    }
}
