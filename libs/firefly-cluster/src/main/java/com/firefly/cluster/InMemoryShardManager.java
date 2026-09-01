package com.firefly.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory shard lease manager with fencing-token semantics.
 */
public final class InMemoryShardManager implements ShardManager {
    private final Object lock = new Object();
    private final Map<Integer, ShardLease> leases = new HashMap<>();
    private final Map<Integer, Long> lastTokens = new HashMap<>();

    @Override
    public Optional<ShardLease> acquire(int shardId, String nodeId, Instant now, Duration leaseDuration) {
        validate(shardId, nodeId, now, leaseDuration);
        synchronized (lock) {
            ShardLease current = leases.get(shardId);
            if (current != null && current.leaseUntil().isAfter(now) && !current.ownerNodeId().equals(nodeId)) {
                return Optional.empty();
            }
            long nextToken = current != null && current.ownerNodeId().equals(nodeId)
                    ? current.fencingToken()
                    : nextToken(shardId);
            ShardLease lease = new ShardLease(shardId, nodeId, now.plus(leaseDuration), nextToken);
            leases.put(shardId, lease);
            return Optional.of(lease);
        }
    }

    @Override
    public Optional<ShardLease> renew(
            int shardId,
            String nodeId,
            long fencingToken,
            Instant now,
            Duration leaseDuration
    ) {
        validate(shardId, nodeId, now, leaseDuration);
        synchronized (lock) {
            ShardLease current = leases.get(shardId);
            if (current == null
                    || !current.ownerNodeId().equals(nodeId)
                    || current.fencingToken() != fencingToken
                    || current.leaseUntil().isBefore(now)) {
                return Optional.empty();
            }
            ShardLease renewed = new ShardLease(shardId, nodeId, now.plus(leaseDuration), fencingToken);
            leases.put(shardId, renewed);
            return Optional.of(renewed);
        }
    }

    @Override
    public boolean release(int shardId, String nodeId, long fencingToken) {
        synchronized (lock) {
            ShardLease current = leases.get(shardId);
            if (current == null
                    || !current.ownerNodeId().equals(nodeId)
                    || current.fencingToken() != fencingToken) {
                return false;
            }
            leases.remove(shardId);
            return true;
        }
    }

    @Override
    public Optional<ShardLease> findLease(int shardId) {
        synchronized (lock) {
            return Optional.ofNullable(leases.get(shardId));
        }
    }

    @Override
    public long countActiveOwnedBy(String nodeId, Instant now) {
        synchronized (lock) {
            return leases.values().stream()
                    .filter(lease -> lease.ownerNodeId().equals(nodeId))
                    .filter(lease -> lease.leaseUntil().isAfter(now))
                    .count();
        }
    }

    private long nextToken(int shardId) {
        long next = lastTokens.getOrDefault(shardId, 0L) + 1;
        lastTokens.put(shardId, next);
        return next;
    }

    private void validate(int shardId, String nodeId, Instant now, Duration leaseDuration) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must not be negative");
        }
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }
}
