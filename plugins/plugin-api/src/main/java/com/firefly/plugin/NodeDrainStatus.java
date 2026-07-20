package com.firefly.plugin;

import com.firefly.cluster.NodeStatus;

public record NodeDrainStatus(
        String nodeId,
        NodeStatus status,
        long ownedShards,
        long activeDispatches,
        long activeExecutionTargets,
        int connectedExecutors,
        boolean readyForOffline
) {
}
