package com.firefly.execution;

import com.firefly.engine.ExecutionCommand;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of replay comparison; a plan is executable only after confirmation when it changed. */
public record ExecutionReplayPlan(
        String sourceExecutionId,
        String sourceRootExecutionId,
        String replayExecutionId,
        boolean dryRun,
        boolean requiresConfirmation,
        boolean failedTargetsOnly,
        List<String> differences,
        ExecutionCommand command
) {
    public ExecutionReplayPlan {
        Objects.requireNonNull(sourceExecutionId, "sourceExecutionId");
        Objects.requireNonNull(sourceRootExecutionId, "sourceRootExecutionId");
        Objects.requireNonNull(replayExecutionId, "replayExecutionId");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
    }

    public Optional<ExecutionCommand> commandIfExecutable(boolean confirmation) {
        if (dryRun || command == null || requiresConfirmation && !confirmation) return Optional.empty();
        return Optional.of(command);
    }
}
