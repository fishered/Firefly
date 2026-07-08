package io.github.nishi.firefly.store;

import io.github.nishi.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface JobRepository {
    void save(JobDefinition definition, Instant initialNextFireTime);

    Optional<ScheduledJobRecord> find(String jobId);

    default List<ScheduledJobRecord> findDue(Instant now, int limit) {
        return findDueBatch(now, limit, limit).records();
    }

    default DueJobBatch findDueBatch(Instant now, int softLimit, int hardLimit) {
        return findDueBatch(now, softLimit, hardLimit, Set.of());
    }

    DueJobBatch findDueBatch(Instant now, int softLimit, int hardLimit, Set<String> excludedJobIds);

    boolean updateNextFireTime(String jobId, Instant expectedCurrentNextFireTime, Instant nextFireTime);

    List<ScheduledJobRecord> list();
}

