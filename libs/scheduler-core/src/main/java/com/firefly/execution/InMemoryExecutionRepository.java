package com.firefly.execution;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryExecutionRepository implements ExecutionRepository {
    private final Object lock = new Object();
    private final Map<String, ExecutionRecord> executions = new HashMap<>();
    private final Map<String, ExecutionTargetRecord> targets = new HashMap<>();

    @Override
    public void saveExecution(ExecutionRecord execution) {
        synchronized (lock) {
            executions.compute(execution.executionId(), (ignored, current) -> {
                if (current == null) return execution;
                if (!current.status().canTransitionTo(execution.status())) return current;
                return new ExecutionRecord(
                        execution.executionId(), execution.rootExecutionId(), execution.runAttempt(),
                        execution.jobId(), execution.scheduledFireTime(),
                        execution.dispatchTime(), execution.dispatchMode(), execution.completionPolicy(),
                        execution.status(), Math.max(current.expectedTargets(), execution.expectedTargets()),
                        Math.max(current.acceptedTargets(), execution.acceptedTargets()), execution.ownerNodeId(),
                        execution.fencingToken(), current.timeoutAt() == null ? execution.timeoutAt() : current.timeoutAt(),
                        current.createdAt(), execution.updatedAt()
                );
            });
        }
    }

    @Override
    public void saveTargets(List<ExecutionTargetRecord> values) {
        synchronized (lock) {
            values.forEach(target -> targets.compute(target.targetExecutionId(), (ignored, current) -> {
                if (current == null) return target;
                if (current.acknowledgedAt() != null || current.completedAt() != null) return current;
                return new ExecutionTargetRecord(
                        target.targetExecutionId(), target.executionId(), target.instanceId(), target.gatewayNodeId(),
                        target.shardIndex(), target.status(), current.attempt() + 1, null, null, "",
                        current.createdAt(), target.updatedAt()
                );
            }));
        }
    }

    @Override
    public ExecutionMutationResult acknowledgeResult(String targetExecutionId, Instant acknowledgedAt) {
        synchronized (lock) {
            ExecutionTargetRecord current = targets.get(targetExecutionId);
            if (current == null) return ExecutionMutationResult.REJECTED;
            if (current.completedAt() != null || current.acknowledgedAt() != null) {
                return ExecutionMutationResult.ALREADY_APPLIED;
            }
            ExecutionRecord parent = executions.get(current.executionId());
            if (parent == null || parent.status().terminal() || current.status() != ExecutionStatus.DISPATCHED) {
                return ExecutionMutationResult.REJECTED;
            }
            targets.put(targetExecutionId, copy(current, ExecutionStatus.RUNNING, acknowledgedAt, null, "", acknowledgedAt));
            refresh(current.executionId(), acknowledgedAt);
            return ExecutionMutationResult.APPLIED;
        }
    }

    @Override
    public ExecutionMutationResult completeResult(
            String targetExecutionId, ExecutionStatus status, String errorMessage, Instant completedAt
    ) {
        synchronized (lock) {
            if (!status.terminal()) throw new IllegalArgumentException("target completion status must be terminal");
            ExecutionTargetRecord current = targets.get(targetExecutionId);
            if (current == null) return ExecutionMutationResult.REJECTED;
            if (current.completedAt() != null) {
                return current.status() == status
                        ? ExecutionMutationResult.ALREADY_APPLIED
                        : ExecutionMutationResult.REJECTED;
            }
            ExecutionRecord parent = executions.get(current.executionId());
            if (parent == null || parent.status().terminal()) return ExecutionMutationResult.REJECTED;
            targets.put(targetExecutionId, copy(
                    current, status,
                    current.acknowledgedAt() == null ? completedAt : current.acknowledgedAt(),
                    completedAt, errorMessage, completedAt
            ));
            refresh(current.executionId(), completedAt);
            return ExecutionMutationResult.APPLIED;
        }
    }

    @Override
    public Optional<ExecutionRecord> findExecution(String executionId) {
        synchronized (lock) {
            return Optional.ofNullable(executions.get(executionId));
        }
    }

    @Override
    public List<ExecutionTargetRecord> listTargets(String executionId) {
        synchronized (lock) {
            return targets.values().stream()
                    .filter(target -> target.executionId().equals(executionId))
                    .sorted(Comparator.comparing(ExecutionTargetRecord::targetExecutionId))
                    .toList();
        }
    }

    @Override
    public List<ExecutionRecord> listRecent(int limit) {
        synchronized (lock) {
            return executions.values().stream()
                    .sorted(Comparator.comparing(ExecutionRecord::createdAt).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public Map<ExecutionStatus, Long> statusCounts() {
        synchronized (lock) {
            return executions.values().stream().collect(java.util.stream.Collectors.groupingBy(
                    ExecutionRecord::status, java.util.stream.Collectors.counting()
            ));
        }
    }

    @Override
    public List<String> expireTimedOutExecutions(Instant now, int limit) {
        synchronized (lock) {
            List<String> expired = executions.values().stream()
                    .filter(execution -> !execution.status().terminal())
                    .filter(execution -> execution.timeoutAt() != null && !execution.timeoutAt().isAfter(now))
                    .sorted(Comparator.comparing(ExecutionRecord::timeoutAt)
                            .thenComparing(ExecutionRecord::executionId))
                    .limit(limit)
                    .map(ExecutionRecord::executionId)
                    .toList();
            expired.forEach(executionId -> {
                ExecutionRecord execution = executions.get(executionId);
                executions.put(executionId, new ExecutionRecord(
                        execution.executionId(), execution.rootExecutionId(), execution.runAttempt(), execution.jobId(),
                        execution.scheduledFireTime(), execution.dispatchTime(), execution.dispatchMode(),
                        execution.completionPolicy(), ExecutionStatus.TIMEOUT, execution.expectedTargets(),
                        execution.acceptedTargets(), execution.ownerNodeId(), execution.fencingToken(), execution.timeoutAt(),
                        execution.createdAt(), now
                ));
                targets.replaceAll((targetId, target) -> target.executionId().equals(executionId)
                                && !target.status().terminal()
                        ? copy(target, ExecutionStatus.TIMEOUT,
                        target.acknowledgedAt(), now, "execution timeout", now)
                        : target);
            });
            return expired;
        }
    }

    private void refresh(String executionId, Instant now) {
        ExecutionRecord execution = executions.get(executionId);
        if (execution == null || execution.status().terminal()) return;
        List<ExecutionTargetRecord> children = listTargets(executionId);
        long succeeded = children.stream().filter(child -> child.status() == ExecutionStatus.SUCCEEDED).count();
        long failed = children.stream().filter(child ->
                child.status() == ExecutionStatus.FAILED || child.status() == ExecutionStatus.TIMEOUT).count();
        long completed = succeeded + failed;
        ExecutionStatus status;
        if (execution.completionPolicy() == com.firefly.domain.ExecutorCompletionPolicy.ANY_SUCCESS && succeeded > 0) {
            status = ExecutionStatus.SUCCEEDED;
        } else if (execution.completionPolicy() == com.firefly.domain.ExecutorCompletionPolicy.QUORUM
                && succeeded >= (execution.expectedTargets() / 2 + 1)) {
            status = ExecutionStatus.SUCCEEDED;
        } else if (completed < execution.expectedTargets()) {
            status = children.stream().anyMatch(child -> child.status() == ExecutionStatus.RUNNING)
                    ? ExecutionStatus.RUNNING : ExecutionStatus.DISPATCHED;
        } else if (execution.completionPolicy() == com.firefly.domain.ExecutorCompletionPolicy.ANY_SUCCESS) {
            status = succeeded > 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        } else if (execution.completionPolicy() == com.firefly.domain.ExecutorCompletionPolicy.QUORUM) {
            status = succeeded >= (execution.expectedTargets() / 2 + 1)
                    ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        } else {
            status = failed == 0 ? ExecutionStatus.SUCCEEDED
                    : succeeded == 0 ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL;
        }
        executions.put(executionId, new ExecutionRecord(
                execution.executionId(), execution.rootExecutionId(), execution.runAttempt(),
                execution.jobId(), execution.scheduledFireTime(), execution.dispatchTime(),
                execution.dispatchMode(), execution.completionPolicy(), status, execution.expectedTargets(),
                execution.acceptedTargets(), execution.ownerNodeId(), execution.fencingToken(), execution.timeoutAt(),
                execution.createdAt(), now
        ));
    }

    private ExecutionTargetRecord copy(
            ExecutionTargetRecord value,
            ExecutionStatus status,
            Instant acknowledgedAt,
            Instant completedAt,
            String error,
            Instant updatedAt
    ) {
        return new ExecutionTargetRecord(
                value.targetExecutionId(), value.executionId(), value.instanceId(), value.gatewayNodeId(),
                value.shardIndex(), status, value.attempt(), acknowledgedAt, completedAt, error,
                value.createdAt(), updatedAt
        );
    }
}
