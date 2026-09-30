package com.firefly.trigger;

import java.time.Instant;
import java.util.Objects;

/** One durable fire time within a backfill operation. */
public record BackfillItem(
        int ordinal,
        Instant fireTime,
        String executionId,
        BackfillItemStatus status,
        String error
) {
    public BackfillItem {
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
        Objects.requireNonNull(fireTime, "fireTime");
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        error = error == null ? "" : error;
    }

    public enum BackfillItemStatus {
        PENDING, DISPATCHED, FAILED
    }
}
