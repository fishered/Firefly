package io.github.nishi.firefly.engine;

import io.github.nishi.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.Objects;

/**
 * Carries one planned execution through local or remote dispatch paths.
 */
public record ExecutionCommand(
        String executionId,
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
        this(executionId, definition, scheduledFireTime, dispatchTime, "local", 1L);
    }

    public ExecutionCommand {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(scheduledFireTime, "scheduledFireTime");
        Objects.requireNonNull(dispatchTime, "dispatchTime");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        if (executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        if (ownerNodeId.isBlank()) {
            throw new IllegalArgumentException("ownerNodeId must not be blank");
        }
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be greater than 0");
        }
    }
}
