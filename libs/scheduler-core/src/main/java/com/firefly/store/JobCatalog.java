package com.firefly.store;

import com.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read/write boundary for the configured job catalog. */
public interface JobCatalog {
    void save(JobDefinition definition, Instant initialNextFireTime);
    Optional<ScheduledJobRecord> find(String jobId);
    List<ScheduledJobRecord> list();
    boolean setEnabled(String jobId, boolean enabled);
    boolean delete(String jobId);
}
