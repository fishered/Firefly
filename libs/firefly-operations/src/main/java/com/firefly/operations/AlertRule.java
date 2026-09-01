package com.firefly.operations;

import java.time.Duration;
import java.util.Objects;

public record AlertRule(
        String ruleId,
        String jobId,
        AlertType type,
        long threshold,
        Duration window,
        Duration cooldown,
        AlertSeverity severity,
        boolean enabled
) {
    public AlertRule {
        Objects.requireNonNull(ruleId, "ruleId");
        jobId = jobId == null ? "" : jobId;
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(severity, "severity");
        if (ruleId.isBlank()) throw new IllegalArgumentException("ruleId must not be blank");
        if (threshold < 1) throw new IllegalArgumentException("threshold must be positive");
        if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("window must be positive");
        if (cooldown.isNegative()) throw new IllegalArgumentException("cooldown must not be negative");
    }
}
