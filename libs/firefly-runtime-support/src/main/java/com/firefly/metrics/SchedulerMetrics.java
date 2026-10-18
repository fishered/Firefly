package com.firefly.metrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Dependency-free runtime metrics shared by the scheduler, coordinator and transports. */
public final class SchedulerMetrics {
    private static final long[] LATENCY_BOUNDS_NANOS = {
            Duration.ofMillis(10).toNanos(),
            Duration.ofMillis(50).toNanos(),
            Duration.ofMillis(100).toNanos(),
            Duration.ofMillis(250).toNanos(),
            Duration.ofMillis(500).toNanos(),
            Duration.ofSeconds(1).toNanos(),
            Duration.ofSeconds(5).toNanos(),
            Duration.ofSeconds(30).toNanos(),
            Long.MAX_VALUE
    };

    private final DurationHistogram scheduleDelay = new DurationHistogram();
    private final DurationHistogram outboxAge = new DurationHistogram();
    private final DurationHistogram acknowledgementDelay = new DurationHistogram();
    private final DurationHistogram executionDuration = new DurationHistogram();
    private final DurationHistogram gatewayForwardDuration = new DurationHistogram();
    private final LongAdder leaseRenewalFailures = new LongAdder();
    private final LongAdder dueBacklogEvents = new LongAdder();
    private final LongAdder outboxDeliveryExhaustions = new LongAdder();
    private final AtomicInteger executorConnections = new AtomicInteger();
    private final LongAdder executorRegistrationRejections = new LongAdder();
    private final LongAdder executorDisconnects = new LongAdder();
    private final LongAdder executorOverloadAcks = new LongAdder();
    private final AtomicInteger executorClientActiveExecutions = new AtomicInteger();
    private final AtomicInteger executorClientQueuedExecutions = new AtomicInteger();
    private final AtomicInteger executorClientMaxConcurrentExecutions = new AtomicInteger();
    private final AtomicInteger executorClientQueueCapacity = new AtomicInteger();
    private final LongAdder gatewayForwardAttempts = new LongAdder();
    private final LongAdder gatewayForwardSuccesses = new LongAdder();
    private final LongAdder gatewayForwardFailures = new LongAdder();
    private final AtomicInteger ownedShards = new AtomicInteger();
    private final AtomicLong clockOffsetMillis = new AtomicLong();
    private final LongAdder clockDriftWarnings = new LongAdder();
    private final LongAdder clockSyncFailures = new LongAdder();
    private final AtomicInteger localWorkerActive = new AtomicInteger();
    private final AtomicInteger localWorkerMaxConcurrency = new AtomicInteger();
    private final LongAdder localWorkerRejections = new LongAdder();
    private final LongAdder batchProgressUpdates = new LongAdder();
    private final LongAdder batchProgressDropped = new LongAdder();
    private final LongAdder batchShardFailures = new LongAdder();
    private final AtomicInteger executorResourceInstances = new AtomicInteger();
    private final AtomicLong executorAvailableCpuMillis = new AtomicLong();
    private final AtomicLong executorAvailableMemoryBytes = new AtomicLong();
    private final AtomicInteger executorResourceActiveExecutions = new AtomicInteger();
    private final LongAdder executorResourceReservationRejections = new LongAdder();
    private final java.util.concurrent.ConcurrentHashMap<String, ExecutorResourceCapacity>
            executorResourceCapacityByExecutor = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger backfillActiveOperations = new AtomicInteger();
    private final AtomicInteger backfillPausedOperations = new AtomicInteger();
    private final AtomicInteger backfillFailedItems = new AtomicInteger();
    private final AtomicLong backfillOldestActiveAgeSeconds = new AtomicLong();
    private final LongAdder backfillOperationsProcessed = new LongAdder();
    private final LongAdder backfillWorkerFailures = new LongAdder();
    private final LongAdder triggerAccepted = new LongAdder();
    private final LongAdder triggerDuplicates = new LongAdder();
    private final LongAdder triggerFailures = new LongAdder();
    private final LongAdder triggerReplays = new LongAdder();
    private final LongAdder triggerSecurityRejections = new LongAdder();

    public void observeScheduleDelay(Duration value) {
        scheduleDelay.observe(value);
    }

    public void observeOutboxAge(Duration value) {
        outboxAge.observe(value);
    }

    public void observeAcknowledgementDelay(Duration value) {
        acknowledgementDelay.observe(value);
    }

    public void observeExecutionDuration(Duration value) {
        executionDuration.observe(value);
    }

    public void recordLeaseRenewalFailure() {
        leaseRenewalFailures.increment();
    }

    public void recordDueBacklog() {
        dueBacklogEvents.increment();
    }

    public void recordOutboxDeliveryExhaustion() {
        outboxDeliveryExhaustions.increment();
    }

    public void executorConnections(int value) {
        executorConnections.set(Math.max(0, value));
    }

    public void recordExecutorRegistrationRejection() {
        executorRegistrationRejections.increment();
    }

    public void recordExecutorDisconnect() {
        executorDisconnects.increment();
    }

    public void recordExecutorOverloadAck() {
        executorOverloadAcks.increment();
    }

    public void executorClientCapacity(int maxConcurrentExecutions, int queueCapacity) {
        executorClientMaxConcurrentExecutions.set(Math.max(0, maxConcurrentExecutions));
        executorClientQueueCapacity.set(Math.max(0, queueCapacity));
    }

    public void executorClientWorkload(int activeExecutions, int queuedExecutions) {
        executorClientActiveExecutions.set(Math.max(0, activeExecutions));
        executorClientQueuedExecutions.set(Math.max(0, queuedExecutions));
    }

    public void recordGatewayForward(Duration duration, boolean success) {
        gatewayForwardAttempts.increment();
        gatewayForwardDuration.observe(duration);
        if (success) gatewayForwardSuccesses.increment();
        else gatewayForwardFailures.increment();
    }

    public void ownedShards(int value) {
        ownedShards.set(Math.max(0, value));
    }

    public void clockOffsetMillis(long value) {
        clockOffsetMillis.set(value);
    }

    public void recordClockDriftWarning() {
        clockDriftWarnings.increment();
    }

    public void recordClockSyncFailure() {
        clockSyncFailures.increment();
    }

    public void localWorkerCapacity(int maxConcurrency) {
        localWorkerMaxConcurrency.set(Math.max(0, maxConcurrency));
    }

    public void localWorkerActive(int active) {
        localWorkerActive.set(Math.max(0, active));
    }

    public void recordLocalWorkerRejection() {
        localWorkerRejections.increment();
    }

    public void recordBatchProgressUpdate() { batchProgressUpdates.increment(); }
    public void recordBatchProgressDropped() { batchProgressDropped.increment(); }
    public void recordBatchShardFailure() { batchShardFailures.increment(); }

    public void executorResourceCapacity(
            int instances, long availableCpuMillis, long availableMemoryBytes, int activeExecutions
    ) {
        executorResourceCapacity("default", instances, availableCpuMillis,
                availableMemoryBytes, activeExecutions);
    }

    public void executorResourceCapacity(
            String executorName, int instances, long availableCpuMillis,
            long availableMemoryBytes, int activeExecutions
    ) {
        if (executorName == null || executorName.isBlank()) {
            throw new IllegalArgumentException("executorName must not be blank");
        }
        executorResourceCapacityByExecutor.put(executorName, new ExecutorResourceCapacity(
                Math.max(0, instances), Math.max(0, availableCpuMillis),
                Math.max(0, availableMemoryBytes), Math.max(0, activeExecutions)
        ));
        executorResourceInstances.set(executorResourceCapacityByExecutor.values().stream()
                .mapToInt(ExecutorResourceCapacity::instances).sum());
        executorAvailableCpuMillis.set(executorResourceCapacityByExecutor.values().stream()
                .mapToLong(ExecutorResourceCapacity::availableCpuMillis).sum());
        executorAvailableMemoryBytes.set(executorResourceCapacityByExecutor.values().stream()
                .mapToLong(ExecutorResourceCapacity::availableMemoryBytes).sum());
        executorResourceActiveExecutions.set(executorResourceCapacityByExecutor.values().stream()
                .mapToInt(ExecutorResourceCapacity::activeExecutions).sum());
    }

    public void recordExecutorResourceReservationRejection() {
        executorResourceReservationRejections.increment();
    }

    public void backfillState(int active, int paused, int failedItems, long oldestActiveAgeSeconds) {
        backfillActiveOperations.set(Math.max(0, active));
        backfillPausedOperations.set(Math.max(0, paused));
        backfillFailedItems.set(Math.max(0, failedItems));
        backfillOldestActiveAgeSeconds.set(Math.max(0, oldestActiveAgeSeconds));
    }

    public void recordBackfillOperationsProcessed(int count) {
        if (count > 0) backfillOperationsProcessed.add(count);
    }

    public void recordBackfillWorkerFailure() {
        backfillWorkerFailures.increment();
    }

    public void recordTriggerAccepted() { triggerAccepted.increment(); }
    public void recordTriggerDuplicate() { triggerDuplicates.increment(); }
    public void recordTriggerFailure() { triggerFailures.increment(); }
    public void recordTriggerReplay() { triggerReplays.increment(); }
    public void recordTriggerSecurityRejection() { triggerSecurityRejections.increment(); }

    public Snapshot snapshot() {
        return new Snapshot(
                scheduleDelay.snapshot(), outboxAge.snapshot(), acknowledgementDelay.snapshot(),
                executionDuration.snapshot(), gatewayForwardDuration.snapshot(),
                leaseRenewalFailures.sum(), dueBacklogEvents.sum(),
                outboxDeliveryExhaustions.sum(), executorConnections.get(),
                executorRegistrationRejections.sum(), executorDisconnects.sum(),
                executorOverloadAcks.sum(), executorClientActiveExecutions.get(),
                executorClientQueuedExecutions.get(), executorClientMaxConcurrentExecutions.get(),
                executorClientQueueCapacity.get(),
                gatewayForwardAttempts.sum(), gatewayForwardSuccesses.sum(), gatewayForwardFailures.sum(),
                ownedShards.get(), clockOffsetMillis.get(),
                clockDriftWarnings.sum(), clockSyncFailures.sum(),
                localWorkerActive.get(), localWorkerMaxConcurrency.get(), localWorkerRejections.sum(),
                batchProgressUpdates.sum(), batchProgressDropped.sum(), batchShardFailures.sum(),
                executorResourceInstances.get(), executorAvailableCpuMillis.get(),
                executorAvailableMemoryBytes.get(), executorResourceActiveExecutions.get(),
                executorResourceReservationRejections.sum(), backfillActiveOperations.get(),
                backfillPausedOperations.get(), backfillFailedItems.get(),
                backfillOldestActiveAgeSeconds.get(), backfillOperationsProcessed.sum(),
                backfillWorkerFailures.sum(), triggerAccepted.sum(), triggerDuplicates.sum(),
                triggerFailures.sum(), triggerReplays.sum(), triggerSecurityRejections.sum()
        );
    }

    public record Snapshot(
            DurationHistogramSnapshot scheduleDelay,
            DurationHistogramSnapshot outboxAge,
            DurationHistogramSnapshot acknowledgementDelay,
            DurationHistogramSnapshot executionDuration,
            DurationHistogramSnapshot gatewayForwardDuration,
            long leaseRenewalFailures,
            long dueBacklogEvents,
            long outboxDeliveryExhaustions,
            int executorConnections,
            long executorRegistrationRejections,
            long executorDisconnects,
            long executorOverloadAcks,
            int executorClientActiveExecutions,
            int executorClientQueuedExecutions,
            int executorClientMaxConcurrentExecutions,
            int executorClientQueueCapacity,
            long gatewayForwardAttempts,
            long gatewayForwardSuccesses,
            long gatewayForwardFailures,
            int ownedShards,
            long clockOffsetMillis,
            long clockDriftWarnings,
            long clockSyncFailures,
            int localWorkerActive,
            int localWorkerMaxConcurrency,
            long localWorkerRejections,
            long batchProgressUpdates,
            long batchProgressDropped,
            long batchShardFailures,
            int executorResourceInstances,
            long executorAvailableCpuMillis,
            long executorAvailableMemoryBytes,
            int executorResourceActiveExecutions,
            long executorResourceReservationRejections,
            int backfillActiveOperations,
            int backfillPausedOperations,
            int backfillFailedItems,
            long backfillOldestActiveAgeSeconds,
            long backfillOperationsProcessed,
            long backfillWorkerFailures,
            long triggerAccepted,
            long triggerDuplicates,
            long triggerFailures,
            long triggerReplays,
            long triggerSecurityRejections
    ) {
        /** Preserves the 1.1.5 constructor for plugins that build synthetic metric snapshots. */
        public Snapshot(
                DurationHistogramSnapshot scheduleDelay,
                DurationHistogramSnapshot outboxAge,
                DurationHistogramSnapshot acknowledgementDelay,
                DurationHistogramSnapshot executionDuration,
                DurationHistogramSnapshot gatewayForwardDuration,
                long leaseRenewalFailures,
                long dueBacklogEvents,
                long outboxDeliveryExhaustions,
                int executorConnections,
                long executorRegistrationRejections,
                long executorDisconnects,
                long executorOverloadAcks,
                int executorClientActiveExecutions,
                int executorClientQueuedExecutions,
                int executorClientMaxConcurrentExecutions,
                int executorClientQueueCapacity,
                long gatewayForwardAttempts,
                long gatewayForwardSuccesses,
                long gatewayForwardFailures,
                int ownedShards,
                long clockOffsetMillis,
                long clockDriftWarnings,
                long clockSyncFailures,
                int localWorkerActive,
                int localWorkerMaxConcurrency,
                long localWorkerRejections,
                long batchProgressUpdates,
                long batchProgressDropped,
                long batchShardFailures
        ) {
            this(
                    scheduleDelay, outboxAge, acknowledgementDelay, executionDuration,
                    gatewayForwardDuration, leaseRenewalFailures, dueBacklogEvents,
                    outboxDeliveryExhaustions, executorConnections, executorRegistrationRejections,
                    executorDisconnects, executorOverloadAcks, executorClientActiveExecutions,
                    executorClientQueuedExecutions, executorClientMaxConcurrentExecutions,
                    executorClientQueueCapacity, gatewayForwardAttempts, gatewayForwardSuccesses,
                    gatewayForwardFailures, ownedShards, clockOffsetMillis, clockDriftWarnings,
                    clockSyncFailures, localWorkerActive, localWorkerMaxConcurrency,
                    localWorkerRejections, batchProgressUpdates, batchProgressDropped,
                    batchShardFailures, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0
            );
        }
    }

    public record DurationHistogramSnapshot(
            List<Bucket> buckets,
            long count,
            double sumSeconds,
            double maxSeconds
    ) {
        public DurationHistogramSnapshot {
            buckets = List.copyOf(buckets);
        }
    }

    public record Bucket(String upperBound, long cumulativeCount) {
    }

    private record ExecutorResourceCapacity(
            int instances, long availableCpuMillis, long availableMemoryBytes, int activeExecutions
    ) {
    }

    private static final class DurationHistogram {
        private final LongAdder[] buckets = java.util.Arrays.stream(LATENCY_BOUNDS_NANOS)
                .mapToObj(ignored -> new LongAdder())
                .toArray(LongAdder[]::new);
        private final LongAdder count = new LongAdder();
        private final LongAdder sumNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void observe(Duration value) {
            long nanos = Math.max(0, safeNanos(value));
            count.increment();
            sumNanos.add(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
            for (int index = 0; index < LATENCY_BOUNDS_NANOS.length; index++) {
                if (nanos <= LATENCY_BOUNDS_NANOS[index]) {
                    buckets[index].increment();
                    return;
                }
            }
        }

        DurationHistogramSnapshot snapshot() {
            List<Bucket> result = new ArrayList<>(buckets.length);
            long cumulative = 0;
            for (int index = 0; index < buckets.length; index++) {
                cumulative += buckets[index].sum();
                long bound = LATENCY_BOUNDS_NANOS[index];
                result.add(new Bucket(
                        bound == Long.MAX_VALUE ? "+Inf" : Double.toString(bound / 1_000_000_000.0),
                        cumulative
                ));
            }
            return new DurationHistogramSnapshot(
                    result, count.sum(), sumNanos.sum() / 1_000_000_000.0,
                    maxNanos.get() / 1_000_000_000.0
            );
        }

        private long safeNanos(Duration value) {
            if (value == null || value.isNegative()) return 0;
            try {
                return value.toNanos();
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }
    }
}
