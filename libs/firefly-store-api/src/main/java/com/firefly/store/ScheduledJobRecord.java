package com.firefly.store;

import com.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.Objects;

public record ScheduledJobRecord(JobDefinition definition, Instant nextFireTime, long version) {
    public ScheduledJobRecord(JobDefinition definition, Instant nextFireTime) {
        this(definition, nextFireTime, 1L);
    }

    public ScheduledJobRecord {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(nextFireTime, "nextFireTime");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
    }
}

