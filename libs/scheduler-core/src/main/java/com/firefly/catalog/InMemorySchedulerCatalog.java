package com.firefly.catalog;

import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobGroupDefinition;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory configuration catalog used by embedded mode and tests.
 */
public final class InMemorySchedulerCatalog implements SchedulerCatalog {
    private final Object lock = new Object();
    private final Map<String, ExecutorDefinition> executors = new HashMap<>();
    private final Map<String, JobGroupDefinition> groups = new HashMap<>();
    private final Map<String, JobDefinition> jobs = new HashMap<>();

    @Override
    public void saveExecutor(ExecutorDefinition executor) {
        Objects.requireNonNull(executor, "executor");
        synchronized (lock) {
            executors.put(executor.name(), executor);
        }
    }

    @Override
    public Optional<ExecutorDefinition> findExecutor(String name) {
        synchronized (lock) {
            return Optional.ofNullable(executors.get(name));
        }
    }

    @Override
    public List<ExecutorDefinition> listExecutors() {
        synchronized (lock) {
            return executors.values().stream()
                    .sorted(Comparator.comparing(ExecutorDefinition::name))
                    .toList();
        }
    }

    @Override
    public void saveJobGroup(JobGroupDefinition group) {
        Objects.requireNonNull(group, "group");
        synchronized (lock) {
            groups.put(group.id(), group);
        }
    }

    @Override
    public Optional<JobGroupDefinition> findJobGroup(String groupId) {
        synchronized (lock) {
            return Optional.ofNullable(groups.get(groupId));
        }
    }

    @Override
    public void saveJob(JobDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        synchronized (lock) {
            jobs.put(definition.id(), definition);
        }
    }

    @Override
    public Optional<JobDefinition> findJob(String jobId) {
        synchronized (lock) {
            return Optional.ofNullable(jobs.get(jobId));
        }
    }

    @Override
    public List<JobDefinition> listJobsByGroup(String groupId) {
        synchronized (lock) {
            return jobs.values().stream()
                    .filter(job -> job.groupId().equals(groupId))
                    .sorted(Comparator.comparing(JobDefinition::id))
                    .toList();
        }
    }
}
