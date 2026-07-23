package com.firefly.server;

import com.firefly.cluster.NodeRegistry;
import com.firefly.cluster.NodeStatus;
import com.firefly.cluster.ShardManager;
import com.firefly.execution.ExecutionRepository;
import com.firefly.executor.RemoteExecutorTransport;
import com.firefly.plugin.NodeDrainStatus;
import com.firefly.plugin.NodeDrainStatusProvider;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Completes a local DRAINING node only after all durable work has left it. */
final class NodeDrainMonitor implements NodeDrainStatusProvider, AutoCloseable {
    private static final Logger log = Logger.getLogger(NodeDrainMonitor.class.getName());

    private final String localNodeId;
    private final NodeRegistry nodeRegistry;
    private final ShardManager shardManager;
    private final JobRepository jobRepository;
    private final ExecutionRepository executionRepository;
    private final RemoteExecutorTransport gateway;
    private final Clock clock;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "firefly-node-drain");
        thread.setDaemon(false);
        return thread;
    });

    NodeDrainMonitor(
            String localNodeId,
            NodeRegistry nodeRegistry,
            ShardManager shardManager,
            JobRepository jobRepository,
            ExecutionRepository executionRepository,
            RemoteExecutorTransport gateway,
            Clock clock
    ) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
        this.shardManager = Objects.requireNonNull(shardManager, "shardManager");
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.gateway = gateway;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void start() {
        timer.scheduleWithFixedDelay(this::checkSafely, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public NodeDrainStatus status(String nodeId) {
        NodeStatus nodeStatus = nodeRegistry.find(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("node not found: " + nodeId))
                .status();
        long ownedShards = shardManager.countActiveOwnedBy(nodeId, clock.instant());
        long activeDispatches = jobRepository.countActiveDispatchesOwnedBy(nodeId);
        long activeTargets = executionRepository.countActiveTargetsByGateway(nodeId);
        int connections = localNodeId.equals(nodeId) && gateway != null
                ? gateway.connectedExecutorCount()
                : 0;
        boolean ready = nodeStatus == NodeStatus.DRAINING
                && ownedShards == 0
                && activeDispatches == 0
                && activeTargets == 0;
        return new NodeDrainStatus(
                nodeId, nodeStatus, ownedShards, activeDispatches, activeTargets, connections, ready
        );
    }

    private void checkSafely() {
        try {
            check();
        } catch (Exception e) {
            log.log(Level.SEVERE, "node drain check failed", e);
        }
    }

    void check() {
        NodeDrainStatus current = status(localNodeId);
        if (!current.readyForOffline()) return;
        if (gateway != null) gateway.disconnectAllExecutors();
        if (nodeRegistry.markOffline(localNodeId)) {
            log.info(() -> "node drain completed: " + localNodeId);
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
