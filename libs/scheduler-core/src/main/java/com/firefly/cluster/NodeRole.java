package com.firefly.cluster;

/**
 * Declares what a Firefly node is allowed to do in a cluster.
 */
public enum NodeRole {
    SCHEDULER,
    STANDBY,
    GATEWAY,
    API,
    EXECUTOR
}
