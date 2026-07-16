package com.firefly.execution;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface ExecutionRepository {
    void saveExecution(ExecutionRecord execution);

    default void startExecution(ExecutionRecord execution, Duration timeout) {
        Instant timeoutAt = execution.updatedAt().plus(timeout);
        saveExecution(new ExecutionRecord(
                execution.executionId(), execution.rootExecutionId(), execution.runAttempt(), execution.jobId(),
                execution.scheduledFireTime(), execution.dispatchTime(), execution.dispatchMode(),
                execution.completionPolicy(), execution.status(), execution.expectedTargets(),
                execution.acceptedTargets(), execution.ownerNodeId(), execution.fencingToken(), timeoutAt,
                execution.createdAt(), execution.updatedAt()
        ));
    }

    void saveTargets(List<ExecutionTargetRecord> targets);

    ExecutionMutationResult acknowledgeResult(String targetExecutionId, Instant acknowledgedAt);

    default boolean acknowledge(String targetExecutionId, Instant acknowledgedAt) {
        return acknowledgeResult(targetExecutionId, acknowledgedAt).accepted();
    }

    ExecutionMutationResult completeResult(
            String targetExecutionId, ExecutionStatus status, String errorMessage, Instant completedAt
    );

    default boolean complete(
            String targetExecutionId, ExecutionStatus status, String errorMessage, Instant completedAt
    ) {
        return completeResult(targetExecutionId, status, errorMessage, completedAt).accepted();
    }

    Optional<ExecutionRecord> findExecution(String executionId);

    List<ExecutionTargetRecord> listTargets(String executionId);

    List<ExecutionRecord> listRecent(int limit);

    default int expireTimedOut(Instant now, int limit) {
        return expireTimedOutExecutions(now, limit).size();
    }

    default List<String> expireTimedOutExecutions(Instant now, int limit) {
        return List.of();
    }

    default int deleteCompletedBefore(Instant cutoff, int limit) {
        return 0;
    }

    default java.util.Map<ExecutionStatus, Long> statusCounts() {
        return java.util.Map.of();
    }
}
