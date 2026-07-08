package io.github.nishi.firefly.domain;

/**
 * Runtime liveness state derived from registration and heartbeat information.
 */
public enum ExecutorInstanceStatus {
    ONLINE,
    OFFLINE
}
