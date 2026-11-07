package com.firefly.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic bounded-store implementation for embedded mode and tests. */
public final class InMemoryTenantCapacityStore implements TenantCapacityStore {
    private final Map<Key, TenantCapacitySample> samples = new HashMap<>();
    private final Map<String, Instant> flushes = new HashMap<>();

    @Override
    public synchronized boolean merge(TenantCapacityFlush flush, Instant now) {
        if (flushes.putIfAbsent(flush.flushId(), flush.sample().bucketStart()) != null) return false;
        Key key = new Key(flush.sample().tenantId(), flush.sample().bucketStart());
        samples.merge(key, flush.sample(), InMemoryTenantCapacityStore::mergeSamples);
        return true;
    }

    @Override
    public synchronized TenantCapacityBaseline baseline(
            String tenantId, Instant from, Instant to, Duration bucketWidth
    ) {
        List<TenantCapacitySample> values = samples.entrySet().stream()
                .filter(entry -> entry.getKey().tenantId.equals(tenantId)
                        && !entry.getKey().bucketStart.isBefore(from)
                        && entry.getKey().bucketStart.isBefore(to))
                .map(Map.Entry::getValue).toList();
        int expected = expectedBuckets(from, to, bucketWidth);
        return aggregate(tenantId, from, to, expected, values);
    }

    @Override
    public synchronized int deleteBefore(Instant cutoff, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        List<Key> keys = samples.keySet().stream().filter(key -> key.bucketStart.isBefore(cutoff))
                .sorted(java.util.Comparator.comparing(Key::bucketStart).thenComparing(Key::tenantId))
                .limit(limit).toList();
        Set<Instant> starts = new HashSet<>();
        for (Key key : keys) {
            samples.remove(key);
            starts.add(key.bucketStart);
        }
        flushes.entrySet().removeIf(entry -> starts.contains(entry.getValue()));
        return keys.size();
    }

    static TenantCapacityBaseline aggregate(
            String tenantId, Instant from, Instant to, int expected, List<TenantCapacitySample> values
    ) {
        List<TenantCapacitySample> observed = values.stream().filter(value -> value.sampleCount() > 0).toList();
        if (observed.isEmpty()) return TenantCapacityBaseline.empty(tenantId, from, to, expected);
        long samples = observed.stream().mapToLong(TenantCapacitySample::sampleCount).sum();
        long minPolicy = observed.stream().mapToLong(TenantCapacitySample::minPolicyVersion).min().orElse(0);
        long maxPolicy = observed.stream().mapToLong(TenantCapacitySample::maxPolicyVersion).max().orElse(0);
        String minRuntime = observed.stream().map(TenantCapacitySample::minRuntimeVersion).min(String::compareTo).orElse("");
        String maxRuntime = observed.stream().map(TenantCapacitySample::maxRuntimeVersion).max(String::compareTo).orElse("");
        List<TenantCapacityBaseline.ResourceBaseline> resources = new ArrayList<>();
        resources.add(resource(TenantCapacityResource.CONCURRENCY, observed, samples,
                TenantCapacitySample::maxActiveExecutions, TenantCapacitySample::saturatedConcurrencySamples,
                TenantCapacitySample::rejectedConcurrency));
        resources.add(resource(TenantCapacityResource.CPU_MILLIS, observed, samples,
                TenantCapacitySample::maxReservedCpuMillis, TenantCapacitySample::saturatedCpuSamples,
                TenantCapacitySample::rejectedCpu));
        resources.add(resource(TenantCapacityResource.MEMORY_BYTES, observed, samples,
                TenantCapacitySample::maxReservedMemoryBytes, TenantCapacitySample::saturatedMemorySamples,
                TenantCapacitySample::rejectedMemory));
        resources.add(resource(TenantCapacityResource.BACKLOG, observed, samples,
                TenantCapacitySample::maxBacklog, TenantCapacitySample::saturatedBacklogSamples,
                TenantCapacitySample::rejectedBacklog));
        resources.add(resource(TenantCapacityResource.TRIGGER_RATE, observed, samples,
                TenantCapacitySample::maxTriggerRate, TenantCapacitySample::saturatedTriggerRateSamples,
                TenantCapacitySample::rejectedTriggerRate));
        double coverage = expected == 0 ? 0 : Math.min(1, observed.size() / (double) expected);
        return new TenantCapacityBaseline(tenantId, from, to, observed.size(), expected, coverage,
                minPolicy, maxPolicy, !minRuntime.equals(maxRuntime), resources);
    }

    private static TenantCapacityBaseline.ResourceBaseline resource(
            TenantCapacityResource resource,
            List<TenantCapacitySample> values,
            long sampleCount,
            java.util.function.ToLongFunction<TenantCapacitySample> value,
            java.util.function.ToLongFunction<TenantCapacitySample> saturated,
            java.util.function.ToLongFunction<TenantCapacitySample> rejected
    ) {
        double average = values.stream().mapToLong(value).average().orElse(0);
        long peak = values.stream().mapToLong(value).max().orElse(0);
        long saturatedCount = values.stream().mapToLong(saturated).sum();
        long rejectionCount = values.stream().mapToLong(rejected).sum();
        return new TenantCapacityBaseline.ResourceBaseline(resource, average, peak,
                sampleCount == 0 ? 0 : Math.min(1, saturatedCount / (double) sampleCount), rejectionCount);
    }

    private static int expectedBuckets(Instant from, Instant to, Duration width) {
        if (width == null || width.isZero() || width.isNegative()) {
            throw new IllegalArgumentException("bucketWidth must be positive");
        }
        long millis = Duration.between(from, to).toMillis();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, (millis + width.toMillis() - 1) / width.toMillis()));
    }

    private static TenantCapacitySample mergeSamples(TenantCapacitySample left, TenantCapacitySample right) {
        return new TenantCapacitySample(left.tenantId(), left.bucketStart(), left.sampleCount() + right.sampleCount(),
                minPositive(left.minPolicyVersion(), right.minPolicyVersion()),
                Math.max(left.maxPolicyVersion(), right.maxPolicyVersion()),
                min(left.minRuntimeVersion(), right.minRuntimeVersion()),
                max(left.maxRuntimeVersion(), right.maxRuntimeVersion()),
                Math.max(left.maxActiveExecutions(), right.maxActiveExecutions()),
                Math.max(left.maxReservedCpuMillis(), right.maxReservedCpuMillis()),
                Math.max(left.maxReservedMemoryBytes(), right.maxReservedMemoryBytes()),
                Math.max(left.maxBacklog(), right.maxBacklog()),
                Math.max(left.maxTriggerRate(), right.maxTriggerRate()),
                left.saturatedConcurrencySamples() + right.saturatedConcurrencySamples(),
                left.saturatedCpuSamples() + right.saturatedCpuSamples(),
                left.saturatedMemorySamples() + right.saturatedMemorySamples(),
                left.saturatedBacklogSamples() + right.saturatedBacklogSamples(),
                left.saturatedTriggerRateSamples() + right.saturatedTriggerRateSamples(),
                left.rejectedConcurrency() + right.rejectedConcurrency(),
                left.rejectedCpu() + right.rejectedCpu(),
                left.rejectedMemory() + right.rejectedMemory(),
                left.rejectedBacklog() + right.rejectedBacklog(),
                left.rejectedTriggerRate() + right.rejectedTriggerRate(),
                left.rejectedDisabled() + right.rejectedDisabled());
    }

    private static long minPositive(long left, long right) {
        if (left == 0) return right;
        if (right == 0) return left;
        return Math.min(left, right);
    }

    private static String min(String left, String right) {
        if (left.isBlank()) return right;
        if (right.isBlank()) return left;
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String max(String left, String right) {
        if (left.isBlank()) return right;
        if (right.isBlank()) return left;
        return left.compareTo(right) >= 0 ? left : right;
    }

    private record Key(String tenantId, Instant bucketStart) { }
}
