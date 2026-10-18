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

    default com.firefly.query.CursorPage<ExecutionRecord> query(ExecutionQuery query) {
        java.util.Comparator<ExecutionRecord> order = java.util.Comparator
                .comparing(ExecutionRecord::createdAt).reversed()
                .thenComparing(ExecutionRecord::executionId, java.util.Comparator.reverseOrder());
        java.util.List<ExecutionRecord> values = listRecent(Integer.MAX_VALUE).stream()
                .filter(value -> query.jobId().isBlank() || value.jobId().equals(query.jobId()))
                .filter(value -> query.status() == null || value.status() == query.status())
                .filter(value -> query.fromInclusive() == null || !value.createdAt().isBefore(query.fromInclusive()))
                .filter(value -> query.toExclusive() == null || value.createdAt().isBefore(query.toExclusive()))
                .filter(value -> query.after() == null
                        || value.createdAt().isBefore(query.after().timestamp())
                        || (value.createdAt().equals(query.after().timestamp())
                        && value.executionId().compareTo(query.after().id()) < 0))
                .sorted(order).limit((long) query.limit() + 1).toList();
        return com.firefly.query.CursorPage.of(values, query.limit(),
                value -> new com.firefly.query.PageCursor(value.createdAt(), value.executionId()));
    }

    default List<ExecutionRecord> listByRootExecutionId(String rootExecutionId) {
        return listRecent(Integer.MAX_VALUE).stream()
                .filter(execution -> execution.rootExecutionId().equals(rootExecutionId))
                .sorted(java.util.Comparator.comparingInt(ExecutionRecord::runAttempt))
                .toList();
    }

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

    default boolean cancelExecution(String executionId, Instant cancelledAt, String reason) {
        return false;
    }

    default long countActiveTargetsByGateway(String gatewayNodeId) {
        return 0L;
    }
}
