package com.firefly.execution;

import com.firefly.domain.JobDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit operator intent for replaying one execution snapshot. */
public record ExecutionReplayRequest(
        String sourceExecutionId,
        JobDefinition currentDefinition,
        ReplayDefinitionSnapshot originalSnapshot,
        ReplayDefinitionSnapshot currentSnapshot,
        boolean dryRun,
        boolean confirmed,
        boolean failedTargetsOnly,
        List<String> failedTargetIds
) {
    public ExecutionReplayRequest {
        if (sourceExecutionId == null || sourceExecutionId.isBlank()) throw new IllegalArgumentException("source execution id is required");
        Objects.requireNonNull(currentDefinition, "currentDefinition");
        Objects.requireNonNull(originalSnapshot, "originalSnapshot");
        Objects.requireNonNull(currentSnapshot, "currentSnapshot");
        failedTargetIds = List.copyOf(Objects.requireNonNull(failedTargetIds, "failedTargetIds"));
        if (!failedTargetsOnly && !failedTargetIds.isEmpty()) throw new IllegalArgumentException("failed target ids require failedTargetsOnly");
    }

    public static ExecutionReplayRequest dryRun(String sourceExecutionId, JobDefinition currentDefinition,
                                                ReplayDefinitionSnapshot original, ReplayDefinitionSnapshot current) {
        return new ExecutionReplayRequest(sourceExecutionId, currentDefinition, original, current,
                true, false, false, List.of());
    }
}
