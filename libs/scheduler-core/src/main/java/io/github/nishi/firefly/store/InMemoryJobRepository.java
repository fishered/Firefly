package io.github.nishi.firefly.store;

import io.github.nishi.firefly.domain.JobDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;

/**
 * In-memory repository optimized for local and test usage.
 *
 * <p>The repository keeps records in two structures: a map for direct lookup and
 * a {@code nextFireTime} ordered index for due-job queries. This avoids scanning
 * all jobs on every scheduler tick while keeping the implementation lightweight.
 */
public final class InMemoryJobRepository implements JobRepository {
    private final Object lock = new Object();
    private final Map<String, ScheduledJobRecord> jobs = new HashMap<>();
    private final NavigableSet<ScheduledJobRecord> nextFireTimeIndex = new TreeSet<>(
            Comparator.comparing(ScheduledJobRecord::nextFireTime)
                    .thenComparing(record -> record.definition().id())
    );

    @Override
    public void save(JobDefinition definition, Instant initialNextFireTime) {
        ScheduledJobRecord record = new ScheduledJobRecord(definition, initialNextFireTime);
        synchronized (lock) {
            ScheduledJobRecord previous = jobs.put(definition.id(), record);
            removeFromIndex(previous);
            addToIndex(record);
        }
    }

    @Override
    public Optional<ScheduledJobRecord> find(String jobId) {
        synchronized (lock) {
            return Optional.ofNullable(jobs.get(jobId));
        }
    }

    @Override
    public List<ScheduledJobRecord> findDue(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        /**
         * Return a stable snapshot instead of removing records from the index here.
         * The scheduler advances each record through updateNextFireTime, which keeps
         * compare-and-set behavior explicit and leaves room for persistent stores.
         */
        synchronized (lock) {
            List<ScheduledJobRecord> due = new ArrayList<>(Math.min(limit, nextFireTimeIndex.size()));
            for (ScheduledJobRecord record : nextFireTimeIndex) {
                if (record.nextFireTime().isAfter(now) || due.size() >= limit) {
                    break;
                }
                due.add(record);
            }
            return List.copyOf(due);
        }
    }

    @Override
    public boolean updateNextFireTime(String jobId, Instant expectedCurrentNextFireTime, Instant nextFireTime) {
        synchronized (lock) {
            ScheduledJobRecord current = jobs.get(jobId);
            if (current == null || !current.nextFireTime().equals(expectedCurrentNextFireTime)) {
                return false;
            }

            ScheduledJobRecord updated = new ScheduledJobRecord(current.definition(), nextFireTime);
            removeFromIndex(current);
            jobs.put(jobId, updated);
            addToIndex(updated);
            return true;
        }
    }

    @Override
    public List<ScheduledJobRecord> list() {
        synchronized (lock) {
            return jobs.values().stream()
                    .sorted(Comparator.comparing(record -> record.definition().id()))
                    .toList();
        }
    }

    private void addToIndex(ScheduledJobRecord record) {
        if (record != null && record.definition().enabled()) {
            nextFireTimeIndex.add(record);
        }
    }

    private void removeFromIndex(ScheduledJobRecord record) {
        if (record != null && record.definition().enabled()) {
            nextFireTimeIndex.remove(record);
        }
    }
}

