package com.firefly.operations;

import com.firefly.execution.ExecutionStatus;

import java.time.Instant;
import java.util.Objects;

/** A deterministic, read-only event reconstructed from execution snapshots. */
public record ExecutionTimelineEvent(
        String eventId,
        String executionId,
        String targetExecutionId,
        Type type,
        Instant occurredAt,
        ExecutionStatus status,
        String message,
        Source source
) {
    public enum Type {
        SCHEDULED,
        DISPATCHED,
        EXECUTION_STATUS,
        ACKNOWLEDGED,
        TARGET_COMPLETED,
        DEADLINE
    }

    public enum Source {
        EXECUTION_SNAPSHOT,
        TARGET_SNAPSHOT
    }

    public ExecutionTimelineEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(source, "source");
        targetExecutionId = targetExecutionId == null ? "" : targetExecutionId;
        message = message == null ? "" : message;
    }
}
