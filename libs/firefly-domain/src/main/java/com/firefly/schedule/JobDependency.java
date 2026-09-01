package com.firefly.schedule;

import java.util.Objects;

public record JobDependency(String jobId, String prerequisiteJobId, int maxWaitAttempts) {
    public JobDependency {
        if (jobId == null || jobId.isBlank() || prerequisiteJobId == null || prerequisiteJobId.isBlank()) {
            throw new IllegalArgumentException("job ids must not be blank");
        }
        if (jobId.equals(prerequisiteJobId)) throw new IllegalArgumentException("self dependency is not allowed");
        if (maxWaitAttempts < 0) throw new IllegalArgumentException("maxWaitAttempts must not be negative");
    }

    public DependencyStatus evaluate(String prerequisiteStatus, int waitAttempts) {
        Objects.requireNonNull(prerequisiteStatus, "prerequisiteStatus");
        return switch (prerequisiteStatus.toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCEEDED" -> DependencyStatus.ALLOWED;
            case "FAILED", "TIMEOUT", "CANCELLED" -> waitAttempts < maxWaitAttempts ? DependencyStatus.WAITING : DependencyStatus.BLOCKED;
            default -> DependencyStatus.WAITING;
        };
    }
}
