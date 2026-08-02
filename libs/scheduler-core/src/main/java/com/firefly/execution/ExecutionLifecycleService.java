package com.firefly.execution;

import java.time.Instant;
import java.util.Objects;

/**
 * Single entry point for operator and maintenance lifecycle transitions.
 * Concrete JDBC repositories perform the execution/target/outbox mutation in one transaction.
 */
public final class ExecutionLifecycleService {
    private final ExecutionRepository executions;

    public ExecutionLifecycleService(ExecutionRepository executions) {
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    public boolean cancel(String executionId, Instant cancelledAt, String reason) {
        return executions.cancelExecution(executionId, cancelledAt, reason);
    }

    public int expireTimeouts(Instant now, int limit) {
        return executions.expireTimedOut(now, limit);
    }
}
