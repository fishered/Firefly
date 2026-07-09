package com.firefly.catalog;

import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobGroupDefinition;

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
