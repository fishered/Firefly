package com.firefly.store;

import com.firefly.domain.JobDefinition;
import com.firefly.cluster.ShardHasher;
import com.firefly.engine.ExecutionCommand;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final Map<String, DispatchOutboxRecord> outbox = new HashMap<>();
    private final Set<String> retryScheduled = new java.util.HashSet<>();
    private long configurationVersion;
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
            configurationVersion++;
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
    public DueJobBatch findDueBatch(Instant now, int softLimit, int hardLimit, Set<String> excludedJobIds) {
        return findDueBatch(now, softLimit, hardLimit, excludedJobIds, null, 1);
    }

    @Override
    public DueJobBatch findDueBatchForShards(
            Instant now,
            int softLimit,
            int hardLimit,
            Set<String> excludedJobIds,
            Set<Integer> shardIds,
            int shardCount
    ) {
        return findDueBatch(now, softLimit, hardLimit, excludedJobIds, Set.copyOf(shardIds), shardCount);
    }

    private DueJobBatch findDueBatch(
            Instant now,
            int softLimit,
            int hardLimit,
            Set<String> excludedJobIds,
            Set<Integer> shardIds,
            int shardCount
    ) {
        if (softLimit <= 0 || hardLimit <= 0) {
            return DueJobBatch.empty();
        }
        Set<String> excluded = Set.copyOf(Objects.requireNonNull(excludedJobIds, "excludedJobIds"));

        /**
         * The soft limit is ignored for records with the same earliest fire time.
         * This keeps one scheduled moment from being split just because many jobs
         * were configured for that exact instant. The hard limit remains as a
         * memory and latency guardrail.
         */
        synchronized (lock) {
            ScheduledJobRecord first = firstDueRecord(now, excluded, shardIds, shardCount);
            if (first == null) {
                return DueJobBatch.empty();
            }

            Instant fireTime = first.nextFireTime();
            List<ScheduledJobRecord> due = new ArrayList<>(Math.min(softLimit, hardLimit));
            boolean truncated = false;
            for (ScheduledJobRecord record : nextFireTimeIndex) {
                if (excluded.contains(record.definition().id())) {
                    continue;
                }
                if (!included(record, shardIds, shardCount)) {
                    continue;
                }
                if (!record.nextFireTime().equals(fireTime)) {
                    break;
                }
                if (due.size() >= hardLimit) {
                    truncated = true;
                    break;
                }
                due.add(record);
            }
            return new DueJobBatch(fireTime, due, truncated);
        }
    }

    private ScheduledJobRecord firstDueRecord(
            Instant now,
            Set<String> excludedJobIds,
            Set<Integer> shardIds,
            int shardCount
    ) {
        for (ScheduledJobRecord record : nextFireTimeIndex) {
            if (record.nextFireTime().isAfter(now)) {
                return null;
            }
            if (!excludedJobIds.contains(record.definition().id()) && included(record, shardIds, shardCount)) {
                return record;
            }
        }
        return null;
    }

    private boolean included(ScheduledJobRecord record, Set<Integer> shardIds, int shardCount) {
        return shardIds == null || shardIds.contains(ShardHasher.shardFor(record.definition().id(), shardCount));
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
    public boolean advanceAndEnqueue(
            String jobId,
            Instant expectedCurrentNextFireTime,
            Instant nextFireTime,
            List<ExecutionCommand> commands
    ) {
        synchronized (lock) {
            ScheduledJobRecord current = jobs.get(jobId);
            if (current == null || !current.nextFireTime().equals(expectedCurrentNextFireTime)) return false;
            ScheduledJobRecord updated = new ScheduledJobRecord(current.definition(), nextFireTime);
            removeFromIndex(current);
            jobs.put(jobId, updated);
            addToIndex(updated);
            commands.forEach(command -> outbox.putIfAbsent(command.executionId(), new DispatchOutboxRecord(
                    command.executionId(), command, dispatchType(command), DispatchOutboxStatus.PENDING, 0,
                    command.dispatchTime(), "", null, null, ""
            )));
            return true;
        }
    }

    @Override
    public List<DispatchOutboxRecord> claimDispatches(
            String claimant,
            Instant now,
            int limit,
            java.time.Duration claimDuration
    ) {
        return claimDispatches(
                claimant, now, limit, claimDuration, java.util.EnumSet.allOf(DispatchType.class)
        );
    }

    @Override
    public List<DispatchOutboxRecord> claimDispatches(
            String claimant,
            Instant now,
            int limit,
            java.time.Duration claimDuration,
            Set<DispatchType> dispatchTypes
    ) {
        synchronized (lock) {
            List<DispatchOutboxRecord> claimed = outbox.values().stream()
                    .filter(record -> dispatchTypes.contains(record.dispatchType()))
                    .filter(record -> record.status() == DispatchOutboxStatus.PENDING
                            || record.status() == DispatchOutboxStatus.RETRY
                            || record.status() == DispatchOutboxStatus.SENT && record.ackDeadline() != null
                            && !record.ackDeadline().isAfter(now)
                            || record.status() == DispatchOutboxStatus.CLAIMED && record.claimUntil() != null
                            && !record.claimUntil().isAfter(now))
                    .filter(record -> !record.availableAt().isAfter(now))
                    .sorted(Comparator.comparing(DispatchOutboxRecord::availableAt))
                    .limit(limit)
                    .toList();
            List<DispatchOutboxRecord> result = new ArrayList<>();
            for (DispatchOutboxRecord record : claimed) {
                DispatchOutboxRecord next = copy(record, DispatchOutboxStatus.CLAIMED,
                        record.attempt() + 1, record.availableAt(), claimant,
                        now.plus(claimDuration), null, record.lastError());
                outbox.put(record.outboxId(), next);
                result.add(next);
            }
            return List.copyOf(result);
        }
    }

    @Override
    public boolean markDispatchSent(String outboxId, Instant ackDeadline) {
        synchronized (lock) {
            DispatchOutboxRecord current = outbox.get(outboxId);
            if (current == null || current.status() != DispatchOutboxStatus.CLAIMED) return false;
            outbox.put(outboxId, copy(current, DispatchOutboxStatus.SENT, current.attempt(),
                    current.availableAt(), "", null, ackDeadline, current.lastError()));
            return true;
        }
    }

    @Override
    public boolean markClaimedDispatchSentFor(
            String outboxId,
            String claimant,
            int claimAttempt,
            java.time.Duration ackTimeout
    ) {
        synchronized (lock) {
            DispatchOutboxRecord current = outbox.get(outboxId);
            if (!ownsClaim(current, claimant, claimAttempt)) return false;
            outbox.put(outboxId, copy(current, DispatchOutboxStatus.SENT, current.attempt(),
                    current.availableAt(), "", null, current.availableAt().plus(ackTimeout), current.lastError()));
            return true;
        }
    }

    @Override
    public boolean acknowledgeDispatch(String executionId, Instant now) {
        synchronized (lock) {
            DispatchOutboxRecord current = outbox.get(executionId);
            if (current == null || current.status() != DispatchOutboxStatus.SENT
                    && current.status() != DispatchOutboxStatus.CLAIMED) return false;
            outbox.put(executionId, copy(current, DispatchOutboxStatus.DONE, current.attempt(),
                    current.availableAt(), "", null, null, current.lastError()));
            return true;
        }
    }

    @Override
    public boolean retryDispatch(String outboxId, Instant availableAt, String error, int maxAttempts) {
        synchronized (lock) {
            DispatchOutboxRecord current = outbox.get(outboxId);
            if (current == null || current.status() != DispatchOutboxStatus.CLAIMED) return false;
            outbox.put(outboxId, copy(current,
                    current.attempt() >= maxAttempts ? DispatchOutboxStatus.DEAD : DispatchOutboxStatus.RETRY,
                    current.attempt(), availableAt, "", null, null, error));
            return true;
        }
    }

    @Override
    public boolean retryClaimedDispatchAfter(
            String outboxId,
            String claimant,
            int claimAttempt,
            java.time.Duration delay,
            String error,
            int maxAttempts
    ) {
        synchronized (lock) {
            DispatchOutboxRecord current = outbox.get(outboxId);
            if (!ownsClaim(current, claimant, claimAttempt)) return false;
            outbox.put(outboxId, copy(current,
                    current.attempt() >= maxAttempts ? DispatchOutboxStatus.DEAD : DispatchOutboxStatus.RETRY,
                    current.attempt(), current.availableAt().plus(delay), "", null, null, error));
            return true;
        }
    }

    @Override
    public boolean completeDispatch(String outboxId, Instant now) {
        return acknowledgeDispatch(outboxId, now);
    }

    @Override
    public boolean enqueueManual(ExecutionCommand command) {
        synchronized (lock) {
            if (outbox.containsKey(command.executionId())) return false;
            outbox.put(command.executionId(), new DispatchOutboxRecord(
                    command.executionId(), command, dispatchType(command), DispatchOutboxStatus.PENDING, 0,
                    command.dispatchTime(), "", null, null, ""
            ));
            return true;
        }
    }

    @Override
    public boolean scheduleExecutionRetry(String sourceExecutionId, Instant requestedAt, boolean timeout) {
        synchronized (lock) {
            DispatchOutboxRecord source = outbox.get(sourceExecutionId);
            if (source == null || !retryScheduled.add(sourceExecutionId)) return false;
            var policy = source.command().definition().retryPolicy();
            if ((timeout && !policy.retryOnTimeout()) || (!timeout && !policy.retryOnFailure())) return false;
            int nextAttempt = source.command().runAttempt() + 1;
            if (nextAttempt >= policy.maxAttempts()) return false;
            String executionId = source.command().rootExecutionId() + "@attempt:" + nextAttempt;
            ExecutionCommand retry = new ExecutionCommand(
                    executionId, source.command().rootExecutionId(), nextAttempt,
                    source.command().definition(), source.command().scheduledFireTime(), requestedAt,
                    source.command().ownerNodeId(), source.command().fencingToken()
            );
            outbox.put(executionId, new DispatchOutboxRecord(
                    executionId, retry, source.dispatchType(), DispatchOutboxStatus.PENDING, 0,
                    requestedAt.plus(policy.delayBeforeAttempt(nextAttempt)), "", null, null, ""
            ));
            return true;
        }
    }

    private boolean ownsClaim(DispatchOutboxRecord record, String claimant, int claimAttempt) {
        return record != null
                && record.status() == DispatchOutboxStatus.CLAIMED
                && record.attempt() == claimAttempt
                && record.claimOwner().equals(claimant);
    }

    private DispatchOutboxRecord copy(
            DispatchOutboxRecord value,
            DispatchOutboxStatus status,
            int attempt,
            Instant availableAt,
            String claimOwner,
            Instant claimUntil,
            Instant ackDeadline,
            String error
    ) {
        return new DispatchOutboxRecord(
                value.outboxId(), value.command(), value.dispatchType(), status, attempt,
                availableAt, claimOwner, claimUntil, ackDeadline, error
        );
    }

    private DispatchType dispatchType(ExecutionCommand command) {
        return command.definition().remote() ? DispatchType.REMOTE : DispatchType.LOCAL;
    }

    @Override
    public List<ScheduledJobRecord> list() {
        synchronized (lock) {
            return jobs.values().stream()
                    .sorted(Comparator.comparing(record -> record.definition().id()))
                    .toList();
        }
    }

    @Override
    public long configurationVersion() {
        synchronized (lock) {
            return configurationVersion;
        }
    }

    @Override
    public boolean setEnabled(String jobId, boolean enabled) {
        synchronized (lock) {
            ScheduledJobRecord current = jobs.get(jobId);
            if (current == null) return false;
            removeFromIndex(current);
            ScheduledJobRecord updated = new ScheduledJobRecord(current.definition().withEnabled(enabled), current.nextFireTime());
            jobs.put(jobId, updated);
            addToIndex(updated);
            configurationVersion++;
            return true;
        }
    }

    @Override
    public boolean delete(String jobId) {
        synchronized (lock) {
            ScheduledJobRecord removed = jobs.remove(jobId);
            removeFromIndex(removed);
            if (removed != null) configurationVersion++;
            return removed != null;
        }
    }

    @Override
    public Map<DispatchOutboxStatus, Long> outboxCounts() {
        synchronized (lock) {
            return outbox.values().stream().collect(java.util.stream.Collectors.groupingBy(
                    DispatchOutboxRecord::status, java.util.stream.Collectors.counting()
            ));
        }
    }

    @Override
    public Optional<Instant> oldestActiveDispatchTime() {
        synchronized (lock) {
            return outbox.values().stream()
                    .filter(record -> record.status() != DispatchOutboxStatus.DONE
                            && record.status() != DispatchOutboxStatus.DEAD)
                    .map(record -> record.command().dispatchTime())
                    .min(Instant::compareTo);
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

