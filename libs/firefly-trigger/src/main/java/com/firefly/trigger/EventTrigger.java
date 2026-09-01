package com.firefly.trigger;

import java.time.Instant;
import java.util.Objects;

public record EventTrigger(String eventId, String eventType, String idempotencyKey, String payload,
                           Instant receivedAt, TriggerStatus status, Instant processedAt) {
    public enum TriggerStatus { RECEIVED, PROCESSED, FAILED }
    public EventTrigger {
        if (eventId == null || eventId.isBlank() || eventType == null || eventType.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("event identifiers must not be blank");
        if (eventId.length() > 256 || eventType.length() > 128 || idempotencyKey.length() > 256) throw new IllegalArgumentException("event identifier too long");
        payload = Objects.requireNonNullElse(payload, "");
        if (payload.length() > 1_048_576) throw new IllegalArgumentException("payload exceeds 1 MiB");
        Objects.requireNonNull(receivedAt, "receivedAt"); Objects.requireNonNull(status, "status");
    }
}
