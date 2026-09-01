package com.firefly.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Coordinates shard ownership so only one scheduler node can advance a shard at a time.
 */
public interface ShardManager {
    Optional<ShardLease> acquire(int shardId, String nodeId, Instant now, Duration leaseDuration);

    Optional<ShardLease> renew(int shardId, String nodeId, long fencingToken, Instant now, Duration leaseDuration);

    default Map<Integer, ShardLease> renewAll(
            Collection<ShardLease> leases,
            String nodeId,
            Instant now,
            Duration leaseDuration
    ) {
        return leases.stream()
                .map(lease -> renew(lease.shardId(), nodeId, lease.fencingToken(), now, leaseDuration))
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableMap(ShardLease::shardId, lease -> lease));
    }

    boolean release(int shardId, String nodeId, long fencingToken);

    Optional<ShardLease> findLease(int shardId);

    default long countActiveOwnedBy(String nodeId, Instant now) {
        return 0L;
    }
}
