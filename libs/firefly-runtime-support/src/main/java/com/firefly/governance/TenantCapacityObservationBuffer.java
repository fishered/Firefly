package com.firefly.governance;

import com.firefly.metrics.SchedulerMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory aggregation that never blocks quota admission on JDBC. */
public final class TenantCapacityObservationBuffer implements AutoCloseable {
    private final TenantCapacityStore store;
    private final SchedulerMetrics metrics;
    private final String sourceId;
    private final String runtimeVersion;
    private final Duration bucketWidth;
    private final int maxBuckets;
    private final Map<Key, Accumulator> buckets = new ConcurrentHashMap<>();

    public TenantCapacityObservationBuffer(
            TenantCapacityStore store, SchedulerMetrics metrics, String sourceId, String runtimeVersion,
            Duration bucketWidth, int maxBuckets
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.sourceId = requireText(sourceId, "sourceId");
        this.runtimeVersion = requireText(runtimeVersion, "runtimeVersion");
        this.bucketWidth = Objects.requireNonNull(bucketWidth, "bucketWidth");
        if (bucketWidth.compareTo(Duration.ofMinutes(1)) < 0 || bucketWidth.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("bucketWidth must be between PT1M and PT1H");
        }
        if (maxBuckets < 1) throw new IllegalArgumentException("maxBuckets must be positive");
        this.maxBuckets = maxBuckets;
    }

    public void observeSnapshot(TenantDefinition policy, TenantUsage usage, Instant observedAt) {
        if (!policy.tenantId().equals(usage.tenantId())) throw new IllegalArgumentException("tenantId mismatch");
        accumulator(policy.tenantId(), observedAt).ifPresent(value -> value.snapshot(policy, usage));
    }

    public void observeAdmission(TenantAdmissionDecision decision, Instant observedAt) {
        Objects.requireNonNull(decision, "decision");
        if (decision.admitted()) return;
        accumulator(decision.usage().tenantId(), observedAt).ifPresent(value -> value.rejected(decision.reason()));
    }

    public void observeRejections(
            String tenantId, TenantAdmissionReason reason, long count, Instant observedAt
    ) {
        if (count <= 0) return;
        accumulator(tenantId, observedAt).ifPresent(value -> value.rejected(reason, count));
    }

    public int flushClosed(Instant now) {
        Instant current = bucketStart(now);
        int flushed = 0;
        for (Map.Entry<Key, Accumulator> entry : new ArrayList<>(buckets.entrySet())) {
            if (!entry.getKey().bucketStart().isBefore(current)) continue;
            if (!buckets.remove(entry.getKey(), entry.getValue())) continue;
            try {
                TenantCapacitySample sample = entry.getValue().sample(entry.getKey());
                store.merge(new TenantCapacityFlush(flushId(entry.getKey()), sample), now);
                flushed++;
            } catch (RuntimeException failure) {
                metrics.recordTenantCapacityFlushFailure("store");
            }
        }
        return flushed;
    }

    public int pendingBuckets() {
        return buckets.size();
    }

    @Override
    public void close() {
        Instant now = Instant.now();
        for (Map.Entry<Key, Accumulator> entry : new ArrayList<>(buckets.entrySet())) {
            if (!buckets.remove(entry.getKey(), entry.getValue())) continue;
            try {
                store.merge(new TenantCapacityFlush(flushId(entry.getKey()), entry.getValue().sample(entry.getKey())), now);
            } catch (RuntimeException failure) {
                metrics.recordTenantCapacityFlushFailure("shutdown");
            }
        }
    }

    private java.util.Optional<Accumulator> accumulator(String tenantId, Instant observedAt) {
        Key key = new Key(tenantId, bucketStart(observedAt));
        Accumulator existing = buckets.get(key);
        if (existing != null) return java.util.Optional.of(existing);
        if (buckets.size() >= maxBuckets) {
            metrics.recordTenantCapacityObservationDropped("buffer_full");
            return java.util.Optional.empty();
        }
        Accumulator created = new Accumulator(runtimeVersion);
        Accumulator raced = buckets.putIfAbsent(key, created);
        return java.util.Optional.of(raced == null ? created : raced);
    }

    private Instant bucketStart(Instant value) {
        long width = bucketWidth.toMillis();
        long millis = value.toEpochMilli();
        return Instant.ofEpochMilli(Math.floorDiv(millis, width) * width);
    }

    private String flushId(Key key) {
        return sourceId + ':' + key.tenantId() + ':' + key.bucketStart().toEpochMilli();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private record Key(String tenantId, Instant bucketStart) { }

    private static final class Accumulator {
        private final String runtimeVersion;
        private long sampleCount;
        private long minPolicyVersion;
        private long maxPolicyVersion;
        private long active;
        private long cpu;
        private long memory;
        private long backlog;
        private long triggerRate;
        private long saturatedConcurrency;
        private long saturatedCpu;
        private long saturatedMemory;
        private long saturatedBacklog;
        private long saturatedTriggerRate;
        private long rejectedConcurrency;
        private long rejectedCpu;
        private long rejectedMemory;
        private long rejectedBacklog;
        private long rejectedTriggerRate;
        private long rejectedDisabled;

        private Accumulator(String runtimeVersion) {
            this.runtimeVersion = runtimeVersion;
        }

        synchronized void snapshot(TenantDefinition policy, TenantUsage usage) {
            sampleCount++;
            minPolicyVersion = minPolicyVersion == 0 ? policy.version() : Math.min(minPolicyVersion, policy.version());
            maxPolicyVersion = Math.max(maxPolicyVersion, policy.version());
            active = Math.max(active, usage.activeExecutions());
            cpu = Math.max(cpu, usage.reservedCpuMillis());
            memory = Math.max(memory, usage.reservedMemoryBytes());
            backlog = Math.max(backlog, usage.backlog());
            triggerRate = Math.max(triggerRate, usage.triggersInWindow());
            if (saturated(policy.maxConcurrent(), usage.activeExecutions())) saturatedConcurrency++;
            if (saturated(policy.maxCpuMillis(), usage.reservedCpuMillis())) saturatedCpu++;
            if (saturated(policy.maxMemoryBytes(), usage.reservedMemoryBytes())) saturatedMemory++;
            if (saturated(policy.maxBacklog(), usage.backlog())) saturatedBacklog++;
            if (saturated(policy.maxTriggerRatePerSecond(), usage.triggersInWindow())) saturatedTriggerRate++;
        }

        synchronized void rejected(TenantAdmissionReason reason) {
            rejected(reason, 1);
        }

        synchronized void rejected(TenantAdmissionReason reason, long count) {
            switch (reason) {
                case CONCURRENCY_QUOTA_EXCEEDED -> rejectedConcurrency += count;
                case CPU_QUOTA_EXCEEDED -> rejectedCpu += count;
                case MEMORY_QUOTA_EXCEEDED -> rejectedMemory += count;
                case BACKLOG_QUOTA_EXCEEDED, OUTBOX_REJECTED -> rejectedBacklog += count;
                case TRIGGER_RATE_QUOTA_EXCEEDED -> rejectedTriggerRate += count;
                case TENANT_DISABLED -> rejectedDisabled += count;
                default -> { }
            }
        }

        synchronized TenantCapacitySample sample(Key key) {
            return new TenantCapacitySample(key.tenantId(), key.bucketStart(), sampleCount,
                    minPolicyVersion, maxPolicyVersion, runtimeVersion, runtimeVersion,
                    active, cpu, memory, backlog, triggerRate,
                    saturatedConcurrency, saturatedCpu, saturatedMemory, saturatedBacklog, saturatedTriggerRate,
                    rejectedConcurrency, rejectedCpu, rejectedMemory, rejectedBacklog, rejectedTriggerRate,
                    rejectedDisabled);
        }

        private static boolean saturated(long limit, long usage) {
            return limit > 0 && usage >= Math.max(1, (long) Math.ceil(limit * 0.9));
        }
    }
}
