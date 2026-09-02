package com.firefly.schedule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Immutable revision tuple used to explain which scheduling inputs produced a fire. */
public record SchedulingInputRevision(String jobId, long jobRevision, long calendarRevision,
                                      long dependencyRevision, Instant effectiveFrom) {
    public SchedulingInputRevision {
        if (jobId == null || jobId.isBlank() || jobRevision < 1 || calendarRevision < 0 || dependencyRevision < 0) {
            throw new IllegalArgumentException("invalid scheduling input revision");
        }
        if (effectiveFrom == null) throw new NullPointerException("effectiveFrom");
    }

    public List<String> differences(SchedulingInputRevision other) {
        if (other == null) throw new NullPointerException("other");
        List<String> result = new ArrayList<>();
        if (!jobId.equals(other.jobId)) result.add("jobId");
        if (jobRevision != other.jobRevision) result.add("jobRevision");
        if (calendarRevision != other.calendarRevision) result.add("calendarRevision");
        if (dependencyRevision != other.dependencyRevision) result.add("dependencyRevision");
        if (!effectiveFrom.equals(other.effectiveFrom)) result.add("effectiveFrom");
        return List.copyOf(result);
    }
}
