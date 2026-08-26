package com.firefly.operations;

import java.time.Instant;
import java.util.Objects;

public record AlertEvent(
        String fingerprint,
        String ruleId,
        String jobId,
        String executionId,
        AlertType type,
        AlertSeverity severity,
        long observedValue,
        Instant observedAt,
        String message
) {
    public AlertEvent {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(observedAt, "observedAt");
        message = message == null ? "" : message;
    }
}
