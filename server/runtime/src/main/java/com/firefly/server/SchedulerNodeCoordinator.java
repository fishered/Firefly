package com.firefly.server;

import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.NodeRegistry;
import com.firefly.cluster.NodeRole;
import com.firefly.cluster.ShardLease;
import com.firefly.cluster.ShardManager;
import com.firefly.cluster.ShardOwnership;
import com.firefly.cluster.SchedulerCoordinationOptions;
import com.firefly.metrics.SchedulerMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Maintains node liveness and deterministic fenced shard ownership. */
public final class SchedulerNodeCoordinator implements ShardOwnership, AutoCloseable {
    private static final Logger log = Logger.getLogger(SchedulerNodeCoordinator.class.getName());

    private final String nodeId;
    private final boolean scheduler;
    private final NodeRegistry nodeRegistry;
    private final ShardManager shardManager;
    private final Clock clock;
    private final int shardCount;
    private final Duration reconcileInterval;
    private final Duration nodeTimeout;
    private final Duration leaseDuration;
    private final SchedulerMetrics metrics;
    private final Map<Integer, ShardLease> owned = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "firefly-node-coordinator");
        thread.setDaemon(false);
        return thread;
    });

    public SchedulerNodeCoordinator(
            String nodeId,
            boolean scheduler,
            NodeRegistry nodeRegistry,
            ShardManager shardManager,
            Clock clock,
            int shardCount
    ) {
        this(nodeId, scheduler, nodeRegistry, shardManager, clock, shardCount,
                SchedulerCoordinationOptions.defaults(), new SchedulerMetrics());
    }

    public SchedulerNodeCoordinator(
            String nodeId,
            boolean scheduler,
            NodeRegistry nodeRegistry,
            ShardManager shardManager,
            Clock clock,
            int shardCount,
            SchedulerCoordinationOptions options
    ) {
        this(nodeId, scheduler, nodeRegistry, shardManager, clock, shardCount, options,
                new SchedulerMetrics());
    }

    public SchedulerNodeCoordinator(
            String nodeId,
            boolean scheduler,
            NodeRegistry nodeRegistry,
            ShardManager shardManager,
            Clock clock,
            int shardCount,
            SchedulerCoordinationOptions options,
            SchedulerMetrics metrics
    ) {
        this.nodeId = nodeId;
        this.scheduler = scheduler;
        this.nodeRegistry = nodeRegistry;
        this.shardManager = shardManager;
        this.clock = clock;
        this.shardCount = shardCount;
        this.nodeTimeout = options.nodeTimeout();
        this.leaseDuration = options.leaseDuration();
        this.reconcileInterval = options.reconcileInterval();
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
    }

    public void start() {
        timer.scheduleWithFixedDelay(
                this::safeReconcile, 0, reconcileInterval.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    @Override
    public Map<Integer, ShardLease> ownedShards() {
        return Map.copyOf(owned);
    }

    private void safeReconcile() {
        try {
            reconcile();
        } catch (Exception e) {
            log.log(Level.SEVERE, "node coordination failed", e);
        }
    }

    void reconcile() {
        Instant now = clock.instant();
        nodeRegistry.heartbeat(nodeId, now);
        if (!scheduler) {
            metrics.ownedShards(0);
            return;
        }
        List<String> schedulers = nodeRegistry.listOnline(now, nodeTimeout).stream()
                .filter(node -> node.roles().contains(NodeRole.SCHEDULER))
                .map(FireflyNode::nodeId)
                .sorted()
                .toList();
        if (schedulers.isEmpty()) {
            metrics.ownedShards(owned.size());
            return;
        }
        for (int shardId = 0; shardId < shardCount; shardId++) {
            int currentShardId = shardId;
            String preferred = preferredOwner(currentShardId, schedulers);
            ShardLease current = owned.get(currentShardId);
            if (!nodeId.equals(preferred)) {
                if (current != null && shardManager.release(currentShardId, nodeId, current.fencingToken())) {
                    owned.remove(currentShardId, current);
                }
                continue;
            }
            if (current == null) {
                shardManager.acquire(currentShardId, nodeId, now, leaseDuration)
                        .ifPresent(lease -> owned.put(currentShardId, lease));
            } else {
                shardManager.renew(currentShardId, nodeId, current.fencingToken(), now, leaseDuration)
                        .ifPresentOrElse(
                                lease -> owned.put(currentShardId, lease),
                                () -> {
                                    owned.remove(currentShardId, current);
                                    metrics.recordLeaseRenewalFailure();
                                }
                        );
            }
        }
        metrics.ownedShards(owned.size());
    }

    private String preferredOwner(int shardId, List<String> nodes) {
        return nodes.stream().max(Comparator.comparingLong(node -> score(shardId, node))).orElseThrow();
    }

    private long score(int shardId, String node) {
        long hash = 0xcbf29ce484222325L;
        String value = shardId + ":" + node;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @Override
    public void close() {
        timer.shutdownNow();
        owned.values().forEach(lease -> shardManager.release(
                lease.shardId(), nodeId, lease.fencingToken()
        ));
        owned.clear();
        nodeRegistry.markOffline(nodeId);
    }
}
