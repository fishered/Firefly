package com.firefly.trigger;

import java.util.Objects;

public record FailedEventTrigger(EventTrigger event, String jobId, String failureReason) {
    public FailedEventTrigger {
        Objects.requireNonNull(event, "event");
        jobId = Objects.requireNonNullElse(jobId, "");
        failureReason = Objects.requireNonNullElse(failureReason, "");
        if (jobId.length() > 128) throw new IllegalArgumentException("job id too long");
        if (failureReason.length() > 4096) throw new IllegalArgumentException("failure reason too long");
    }
}
