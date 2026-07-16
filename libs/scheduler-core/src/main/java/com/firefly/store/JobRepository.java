package com.firefly.store;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;

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

    default DueJobBatch findDueBatchForShards(
            Instant now,
            int softLimit,
            int hardLimit,
            Set<String> excludedJobIds,
            Set<Integer> shardIds,
            int shardCount
    ) {
        return findDueBatch(now, softLimit, hardLimit, excludedJobIds);
    }

    boolean updateNextFireTime(String jobId, Instant expectedCurrentNextFireTime, Instant nextFireTime);

    default boolean updateNextFireTimeWithLease(
            String jobId,
            Instant expectedCurrentNextFireTime,
            Instant nextFireTime,
            String ownerNodeId,
            long fencingToken
    ) {
        return updateNextFireTime(jobId, expectedCurrentNextFireTime, nextFireTime);
    }

    default boolean advanceAndEnqueue(
            String jobId,
            Instant expectedCurrentNextFireTime,
            Instant nextFireTime,
            List<ExecutionCommand> commands
    ) {
        if (commands.isEmpty()) {
            return updateNextFireTime(jobId, expectedCurrentNextFireTime, nextFireTime);
        }
        ExecutionCommand first = commands.getFirst();
        return updateNextFireTimeWithLease(
                jobId, expectedCurrentNextFireTime, nextFireTime, first.ownerNodeId(), first.fencingToken()
        );
    }

    default List<DispatchOutboxRecord> claimDispatches(
            String claimant,
            Instant now,
            int limit,
            java.time.Duration claimDuration
    ) {
        return List.of();
    }

    default List<DispatchOutboxRecord> claimDispatches(
            String claimant,
            Instant now,
            int limit,
            java.time.Duration claimDuration,
            Set<DispatchType> dispatchTypes
    ) {
        return claimDispatches(claimant, now, limit, claimDuration);
    }

    default boolean markDispatchSent(String outboxId, Instant ackDeadline) {
        return false;
    }

    default boolean markDispatchSentFor(String outboxId, java.time.Duration ackTimeout) {
        return markDispatchSent(outboxId, Instant.now().plus(ackTimeout));
    }

    default boolean markClaimedDispatchSentFor(
            String outboxId,
            String claimant,
            int claimAttempt,
            java.time.Duration ackTimeout
    ) {
        return markDispatchSentFor(outboxId, ackTimeout);
    }

    default boolean acknowledgeDispatch(String executionId, Instant now) {
        return false;
    }

    default boolean retryDispatch(String outboxId, Instant availableAt, String error, int maxAttempts) {
        return false;
    }

    default boolean retryDispatchAfter(
            String outboxId,
            java.time.Duration delay,
            String error,
            int maxAttempts
    ) {
        return retryDispatch(outboxId, Instant.now().plus(delay), error, maxAttempts);
    }

    default boolean retryClaimedDispatchAfter(
            String outboxId,
            String claimant,
            int claimAttempt,
            java.time.Duration delay,
            String error,
            int maxAttempts
    ) {
        return retryDispatchAfter(outboxId, delay, error, maxAttempts);
    }

    default boolean completeDispatch(String outboxId, Instant now) {
        return false;
    }

    default boolean enqueueManual(ExecutionCommand command) {
        return false;
    }

    default boolean scheduleExecutionRetry(String sourceExecutionId, Instant requestedAt, boolean timeout) {
        return false;
    }

    List<ScheduledJobRecord> list();

    default List<ScheduledJobRecord> listForShards(Set<Integer> shardIds, int shardCount) {
        return list().stream()
                .filter(record -> shardIds.contains(com.firefly.cluster.ShardHasher.shardFor(
                        record.definition().id(), shardCount
                )))
                .toList();
    }

    default long configurationVersion() {
        return 0L;
    }

    boolean setEnabled(String jobId, boolean enabled);

    boolean delete(String jobId);

    default java.util.Map<DispatchOutboxStatus, Long> outboxCounts() {
        return java.util.Map.of();
    }

    default List<DispatchOutboxRecord> listDeadDispatches(int limit) {
        return List.of();
    }

    default boolean requeueDeadDispatch(String outboxId, Instant now) {
        return false;
    }

    default java.util.Optional<Instant> oldestActiveDispatchTime() {
        return java.util.Optional.empty();
    }
}

