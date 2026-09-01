package com.firefly.cluster;

import java.util.Map;

/** Supplies the scheduler with its current fenced shard leases. */
@FunctionalInterface
public interface ShardOwnership {
    Map<Integer, ShardLease> ownedShards();
}
