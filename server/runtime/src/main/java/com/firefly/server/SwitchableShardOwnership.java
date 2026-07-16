package com.firefly.server;

import com.firefly.cluster.ShardLease;
import com.firefly.cluster.ShardOwnership;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SwitchableShardOwnership implements ShardOwnership {
    private final AtomicReference<ShardOwnership> delegate = new AtomicReference<>(Map::of);

    public void install(ShardOwnership ownership) {
        delegate.set(Objects.requireNonNull(ownership, "ownership"));
    }

    @Override
    public Map<Integer, ShardLease> ownedShards() {
        return delegate.get().ownedShards();
    }
}
