package com.firefly.execution;

import java.time.Instant;
import java.util.Objects;

public record ExecutionTargetRecord(
        String targetExecutionId,
        String executionId,
        String instanceId,
        String gatewayNodeId,
        Integer shardIndex,
        ExecutionStatus status,
        int attempt,
        Instant acknowledgedAt,
        Instant completedAt,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public ExecutionTargetRecord {
        Objects.requireNonNull(targetExecutionId, "targetExecutionId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(gatewayNodeId, "gatewayNodeId");
        Objects.requireNonNull(status, "status");
        errorMessage = errorMessage == null ? "" : errorMessage;
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
