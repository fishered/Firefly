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
        Instant dispatchTime
) {
    public ExecutionCommand {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(scheduledFireTime, "scheduledFireTime");
        Objects.requireNonNull(dispatchTime, "dispatchTime");
        if (executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
    }
}
