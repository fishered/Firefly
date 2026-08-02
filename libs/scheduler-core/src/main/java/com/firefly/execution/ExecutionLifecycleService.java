package com.firefly.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Single entry point for operator and maintenance lifecycle transitions.
 * Concrete JDBC repositories perform the execution/target/outbox mutation in one transaction.
 */
public final class ExecutionLifecycleService {
    private final ExecutionRepository executions;
    private final ExecutionLifecycleStore lifecycleStore;

    public ExecutionLifecycleService(ExecutionRepository executions) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.lifecycleStore = null;
    }

    public ExecutionLifecycleService(ExecutionLifecycleStore lifecycleStore) {
        this.executions = null;
        this.lifecycleStore = Objects.requireNonNull(lifecycleStore, "lifecycleStore");
    }

    public boolean cancel(String executionId, Instant cancelledAt, String reason) {
        return lifecycleStore != null
                ? lifecycleStore.cancel(executionId, cancelledAt, reason)
                : executions.cancelExecution(executionId, cancelledAt, reason);
    }

    public int expireTimeouts(Instant now, int limit) {
        return lifecycleStore != null
                ? lifecycleStore.expireTimeouts(now, limit).size()
                : executions.expireTimedOut(now, limit);
    }
}
