package com.firefly.schedule;

import com.firefly.store.JobRepository;
import java.time.Instant;
import java.util.Objects;

/** Evaluates all prerequisites in one business-time window. */
public final class DependencyEvaluator {
    private final JobRepository jobs;
    public DependencyEvaluator(JobRepository jobs) { this.jobs = Objects.requireNonNull(jobs, "jobs"); }
    public DependencyStatus evaluate(JobDependency dependency, Instant businessTime, int waitAttempts) {
        return dependency.evaluate(jobs.dependencyStatus(dependency.prerequisiteJobId(), businessTime), waitAttempts);
    }
}
