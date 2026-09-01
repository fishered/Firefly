package com.firefly.store;

import com.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.Objects;

public record ScheduledJobRecord(JobDefinition definition, Instant nextFireTime) {
    public ScheduledJobRecord {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(nextFireTime, "nextFireTime");
    }
}

