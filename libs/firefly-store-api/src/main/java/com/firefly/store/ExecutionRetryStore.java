package com.firefly.store;

import com.firefly.engine.ExecutionCommand;

import java.time.Instant;

/** Boundary for manual dispatch and execution retry scheduling. */
public interface ExecutionRetryStore {
    boolean enqueueManual(ExecutionCommand command);
    boolean scheduleExecutionRetry(String sourceExecutionId, Instant requestedAt, boolean timeout);
}
