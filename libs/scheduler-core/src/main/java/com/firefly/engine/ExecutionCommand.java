package com.firefly.engine;

import com.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.Objects;

/**
 * Carries one planned execution through local or remote dispatch paths.
 */
public record ExecutionCommand(
        String executionId,
        String rootExecutionId,
        int runAttempt,
        JobDefinition definition,
        Instant scheduledFireTime,
        Instant dispatchTime,
        String ownerNodeId,
        long fencingToken
) {
    public ExecutionCommand(
            String executionId,
            JobDefinition definition,
            Instant scheduledFireTime,
            Instant dispatchTime
    ) {
        this(executionId, executionId, 0, definition, scheduledFireTime, dispatchTime, "local", 1L);
    }

    public ExecutionCommand(
            String executionId,
            JobDefinition definition,
            Instant scheduledFireTime,
            Instant dispatchTime,
            String ownerNodeId,
            long fencingToken
    ) {
        this(executionId, executionId, 0, definition, scheduledFireTime, dispatchTime, ownerNodeId, fencingToken);
    }

    public ExecutionCommand {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(rootExecutionId, "rootExecutionId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(scheduledFireTime, "scheduledFireTime");
        Objects.requireNonNull(dispatchTime, "dispatchTime");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        if (executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        if (rootExecutionId.isBlank()) throw new IllegalArgumentException("rootExecutionId must not be blank");
        if (runAttempt < 0) throw new IllegalArgumentException("runAttempt must not be negative");
        if (ownerNodeId.isBlank()) {
            throw new IllegalArgumentException("ownerNodeId must not be blank");
        }
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be greater than 0");
        }
    }
}
