package com.firefly.store;

import java.time.Instant;
import java.util.Objects;

public record JobHistoryRecord(
        String historyId,
        String jobId,
        long version,
        String action,
        String actor,
        String beforePayload,
        String afterPayload,
        Instant occurredAt
) {
    public JobHistoryRecord {
        Objects.requireNonNull(historyId, "historyId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(action, "action");
        actor = actor == null ? "" : actor;
        beforePayload = beforePayload == null ? "" : beforePayload;
        afterPayload = afterPayload == null ? "" : afterPayload;
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
