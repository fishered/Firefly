package com.firefly.trigger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable event group released as one execution. */
public record AggregatedEvent(
        String jobId,
        String aggregationKey,
        String latestPayload,
        int eventCount,
        Instant firstReceivedAt,
        Instant readyAt,
        List<String> idempotencyKeys
) {
    public AggregatedEvent {
        if (jobId == null || jobId.isBlank() || aggregationKey == null || aggregationKey.isBlank()) {
            throw new IllegalArgumentException("aggregate identity is required");
        }
        latestPayload = Objects.requireNonNullElse(latestPayload, "");
        if (eventCount < 1) throw new IllegalArgumentException("eventCount must be positive");
        Objects.requireNonNull(firstReceivedAt, "firstReceivedAt");
        Objects.requireNonNull(readyAt, "readyAt");
        idempotencyKeys = List.copyOf(Objects.requireNonNull(idempotencyKeys, "idempotencyKeys"));
        if (idempotencyKeys.size() != eventCount) throw new IllegalArgumentException("event keys do not match eventCount");
    }
}
