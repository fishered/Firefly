package com.firefly.domain;

/** Defines which remote targets participate in a retry attempt. */
public enum ExecutorRetryScope {
    FAILED_TARGETS_ONLY,
    ALL_TARGETS
}
