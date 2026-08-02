package com.firefly.execution;

import java.time.Instant;
import java.util.List;

/** Persistence boundary for lifecycle transitions that span execution, targets and dispatch outbox. */
public interface ExecutionLifecycleStore {
    boolean cancel(String executionId, Instant cancelledAt, String reason);
    List<String> expireTimeouts(Instant now, int limit);
}
