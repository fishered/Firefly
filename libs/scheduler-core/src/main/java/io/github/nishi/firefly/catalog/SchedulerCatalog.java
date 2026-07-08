package io.github.nishi.firefly.catalog;

import io.github.nishi.firefly.domain.ExecutorDefinition;
import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.domain.JobGroupDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Stores scheduler configuration separately from runtime next-fire cursors.
 */
public interface SchedulerCatalog {
    void saveExecutor(ExecutorDefinition executor);

    Optional<ExecutorDefinition> findExecutor(String name);

    void saveJobGroup(JobGroupDefinition group);

    Optional<JobGroupDefinition> findJobGroup(String groupId);

    void saveJob(JobDefinition definition);

    Optional<JobDefinition> findJob(String jobId);

    List<JobDefinition> listJobsByGroup(String groupId);
}
