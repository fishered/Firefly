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

    default java.util.Optional<com.firefly.schedule.CalendarDefinition> findCalendar(String calendarId) {
        return java.util.Optional.empty();
    }

    default void saveCalendar(com.firefly.schedule.CalendarDefinition calendar) {
        throw unsupported("saveCalendar");
    }

    default java.util.List<com.firefly.schedule.CalendarDefinition> listCalendars() { return java.util.List.of(); }

    default String dependencyStatus(String prerequisiteJobId, Instant businessTime) {
        return "PENDING";
    }

    /** Durable wait-attempt state used by dependency-gated scheduling. */
    default int dependencyWaitAttempts(String jobId, Instant businessTime) { return 0; }

    default void recordDependencyWait(String jobId, Instant businessTime, int attempts, Instant nextCheckAt) { }

    default void clearDependencyWait(String jobId, Instant businessTime) { }

    /** Optional durable business-condition hook; repositories may delegate to an external condition store. */
    default com.firefly.schedule.ConditionStatus conditionStatus(String jobId, Instant businessTime) {
        return com.firefly.schedule.ConditionStatus.ALLOWED;
    }

    default void setConditionStatus(String jobId, Instant businessTime,
                                     com.firefly.schedule.ConditionStatus status, String reason) { }

    default boolean openDependencyGate(com.firefly.schedule.DependencyGate gate) { return true; }
    default List<com.firefly.schedule.DependencyGate> dueDependencyGates(Instant now, int limit) { return List.of(); }
    default boolean claimDependencyGate(String gateId, int expectedAttempts, String owner, Instant claimUntil) { return true; }
    default boolean updateDependencyGate(com.firefly.schedule.DependencyGate gate) { return true; }

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

    default List<Boolean> advanceAndEnqueueBatch(List<SchedulingAdvance> advances) {
        return advances.stream().map(advance -> advanceAndEnqueue(
                advance.jobId(), advance.expectedCurrentNextFireTime(),
                advance.nextFireTime(), advance.commands()
        )).toList();
    }

    default List<DispatchOutboxRecord> claimDispatches(
            String claimant,
            Instant now,
            int limit,
            java.time.Duration claimDuration
    ) {
        throw unsupported("claimDispatches");
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
        throw unsupported("markDispatchSent");
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
        throw unsupported("acknowledgeDispatch");
    }

    default boolean retryDispatch(String outboxId, Instant availableAt, String error, int maxAttempts) {
        throw unsupported("retryDispatch");
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

    default boolean deferClaimedDispatch(
            String outboxId,
            String claimant,
            int claimAttempt,
            java.time.Duration delay,
            String reason
    ) {
        return retryClaimedDispatchAfter(outboxId, claimant, claimAttempt, delay, reason, Integer.MAX_VALUE);
    }

    default boolean completeDispatch(String outboxId, Instant now) {
        throw unsupported("completeDispatch");
    }

    default boolean enqueueManual(ExecutionCommand command) {
        throw unsupported("enqueueManual");
    }

    default boolean scheduleExecutionRetry(String sourceExecutionId, Instant requestedAt, boolean timeout) {
        throw unsupported("scheduleExecutionRetry");
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
        throw unsupported("outboxCounts");
    }

    default List<DispatchOutboxRecord> listDeadDispatches(int limit) {
        throw unsupported("listDeadDispatches");
    }

    default boolean requeueDeadDispatch(String outboxId, Instant now) {
        throw unsupported("requeueDeadDispatch");
    }

    default int requeueDeadDispatches(List<String> outboxIds, Instant now) {
        int requeued = 0;
        for (String outboxId : outboxIds) {
            if (requeueDeadDispatch(outboxId, now)) requeued++;
        }
        return requeued;
    }

    default boolean cancelDispatch(String executionId, Instant now, String reason) {
        throw unsupported("cancelDispatch");
    }

    default java.util.Optional<Instant> oldestActiveDispatchTime() {
        return java.util.Optional.empty();
    }

    default long countActiveDispatchesOwnedBy(String nodeId) {
        throw unsupported("countActiveDispatchesOwnedBy");
    }

    default java.util.Map<Integer, Long> dueCountsByShard(Instant now, int shardCount) {
        return list().stream()
                .filter(record -> record.definition().enabled())
                .filter(record -> !record.nextFireTime().isAfter(now))
                .collect(java.util.stream.Collectors.groupingBy(
                        record -> com.firefly.cluster.ShardHasher.shardFor(record.definition().id(), shardCount),
                        java.util.stream.Collectors.counting()
                ));
    }

    private static UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException("JobRepository capability is not implemented: " + operation);
    }
}

