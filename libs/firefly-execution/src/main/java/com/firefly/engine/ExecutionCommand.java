package com.firefly.engine;

import com.firefly.domain.JobDefinition;
import com.firefly.tracing.TraceCarrier;

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
        long fencingToken,
        TraceCarrier traceCarrier
) {
    public ExecutionCommand(
            String executionId,
            JobDefinition definition,
            Instant scheduledFireTime,
            Instant dispatchTime
    ) {
        this(executionId, executionId, 0, definition, scheduledFireTime, dispatchTime,
                "local", 1L, TraceCarrier.empty());
    }

    public ExecutionCommand(
            String executionId,
            JobDefinition definition,
            Instant scheduledFireTime,
            Instant dispatchTime,
            String ownerNodeId,
            long fencingToken
    ) {
        this(executionId, executionId, 0, definition, scheduledFireTime, dispatchTime,
                ownerNodeId, fencingToken, TraceCarrier.empty());
    }

    public ExecutionCommand(
            String executionId,
            String rootExecutionId,
            int runAttempt,
            JobDefinition definition,
            Instant scheduledFireTime,
            Instant dispatchTime,
            String ownerNodeId,
            long fencingToken
    ) {
        this(executionId, rootExecutionId, runAttempt, definition, scheduledFireTime, dispatchTime,
                ownerNodeId, fencingToken, TraceCarrier.empty());
    }

    public ExecutionCommand {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(rootExecutionId, "rootExecutionId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(scheduledFireTime, "scheduledFireTime");
        Objects.requireNonNull(dispatchTime, "dispatchTime");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        traceCarrier = traceCarrier == null ? TraceCarrier.empty() : traceCarrier;
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

    public ExecutionCommand withTraceCarrier(TraceCarrier carrier) {
        return new ExecutionCommand(
                executionId, rootExecutionId, runAttempt, definition, scheduledFireTime,
                dispatchTime, ownerNodeId, fencingToken, carrier
        );
    }
}
