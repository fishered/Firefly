package com.firefly.cluster;

/**
 * Heartbeat-derived node state.
 */
public enum NodeStatus {
    STARTING,
    ONLINE,
    DRAINING,
    OFFLINE
}
