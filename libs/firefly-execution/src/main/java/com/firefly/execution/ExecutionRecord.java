package com.firefly.execution;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;

import java.time.Instant;
import java.util.Objects;

public record ExecutionRecord(
        String executionId,
        String rootExecutionId,
        int runAttempt,
        String jobId,
        Instant scheduledFireTime,
        Instant dispatchTime,
        ExecutorDispatchMode dispatchMode,
        ExecutorCompletionPolicy completionPolicy,
        ExecutionStatus status,
        int expectedTargets,
        int acceptedTargets,
        String ownerNodeId,
        long fencingToken,
        Instant timeoutAt,
        Instant createdAt,
        Instant updatedAt
) {
    public ExecutionRecord(
            String executionId, String rootExecutionId, int runAttempt, String jobId,
            Instant scheduledFireTime, Instant dispatchTime, ExecutorDispatchMode dispatchMode,
            ExecutorCompletionPolicy completionPolicy, ExecutionStatus status, int expectedTargets,
            int acceptedTargets, String ownerNodeId, long fencingToken, Instant createdAt, Instant updatedAt
    ) {
        this(executionId, rootExecutionId, runAttempt, jobId, scheduledFireTime, dispatchTime,
                dispatchMode, completionPolicy, status, expectedTargets, acceptedTargets, ownerNodeId,
                fencingToken, null, createdAt, updatedAt);
    }

    public ExecutionRecord(
            String executionId, String jobId, Instant scheduledFireTime, Instant dispatchTime,
            ExecutorDispatchMode dispatchMode, ExecutorCompletionPolicy completionPolicy,
            ExecutionStatus status, int expectedTargets, int acceptedTargets, String ownerNodeId,
            long fencingToken, Instant createdAt, Instant updatedAt
    ) {
        this(executionId, executionId, 0, jobId, scheduledFireTime, dispatchTime, dispatchMode,
                completionPolicy, status, expectedTargets, acceptedTargets, ownerNodeId, fencingToken,
                null, createdAt, updatedAt);
    }

    public ExecutionRecord {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(rootExecutionId, "rootExecutionId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(scheduledFireTime, "scheduledFireTime");
        Objects.requireNonNull(dispatchTime, "dispatchTime");
        Objects.requireNonNull(dispatchMode, "dispatchMode");
        Objects.requireNonNull(completionPolicy, "completionPolicy");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (runAttempt < 0) throw new IllegalArgumentException("runAttempt must not be negative");
    }
}
