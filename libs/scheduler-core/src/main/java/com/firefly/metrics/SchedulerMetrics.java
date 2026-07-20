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
    private final LongAdder gatewayForwardAttempts = new LongAdder();
    private final LongAdder gatewayForwardSuccesses = new LongAdder();
    private final LongAdder gatewayForwardFailures = new LongAdder();
    private final AtomicInteger ownedShards = new AtomicInteger();
    private final AtomicLong clockOffsetMillis = new AtomicLong();
    private final LongAdder clockDriftWarnings = new LongAdder();
    private final LongAdder clockSyncFailures = new LongAdder();

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

    public Snapshot snapshot() {
        return new Snapshot(
                scheduleDelay.snapshot(), outboxAge.snapshot(), acknowledgementDelay.snapshot(),
                executionDuration.snapshot(), gatewayForwardDuration.snapshot(),
                leaseRenewalFailures.sum(), dueBacklogEvents.sum(),
                outboxDeliveryExhaustions.sum(), executorConnections.get(),
                executorRegistrationRejections.sum(), executorDisconnects.sum(),
                gatewayForwardAttempts.sum(), gatewayForwardSuccesses.sum(), gatewayForwardFailures.sum(),
                ownedShards.get(), clockOffsetMillis.get(),
                clockDriftWarnings.sum(), clockSyncFailures.sum()
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
            long gatewayForwardAttempts,
            long gatewayForwardSuccesses,
            long gatewayForwardFailures,
            int ownedShards,
            long clockOffsetMillis,
            long clockDriftWarnings,
            long clockSyncFailures
    ) {
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
