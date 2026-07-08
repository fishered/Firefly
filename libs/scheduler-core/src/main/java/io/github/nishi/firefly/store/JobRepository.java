package io.github.nishi.firefly.store;

import io.github.nishi.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {
    void save(JobDefinition definition, Instant initialNextFireTime);

    Optional<ScheduledJobRecord> find(String jobId);

    List<ScheduledJobRecord> findDue(Instant now, int limit);

    boolean updateNextFireTime(String jobId, Instant expectedCurrentNextFireTime, Instant nextFireTime);

    List<ScheduledJobRecord> list();
}

