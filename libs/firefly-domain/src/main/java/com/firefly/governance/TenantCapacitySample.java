package com.firefly.governance;

import java.time.Instant;
import java.util.Objects;

/** One mergeable capacity observation bucket. */
public record TenantCapacitySample(
        String tenantId,
        Instant bucketStart,
        long sampleCount,
        long minPolicyVersion,
        long maxPolicyVersion,
        String minRuntimeVersion,
        String maxRuntimeVersion,
        long maxActiveExecutions,
        long maxReservedCpuMillis,
        long maxReservedMemoryBytes,
        long maxBacklog,
        long maxTriggerRate,
        long saturatedConcurrencySamples,
        long saturatedCpuSamples,
        long saturatedMemorySamples,
        long saturatedBacklogSamples,
        long saturatedTriggerRateSamples,
        long rejectedConcurrency,
        long rejectedCpu,
        long rejectedMemory,
        long rejectedBacklog,
        long rejectedTriggerRate,
        long rejectedDisabled
) {
    public TenantCapacitySample {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(bucketStart, "bucketStart");
        Objects.requireNonNull(minRuntimeVersion, "minRuntimeVersion");
        Objects.requireNonNull(maxRuntimeVersion, "maxRuntimeVersion");
        if (tenantId.isBlank() || sampleCount < 0 || minPolicyVersion < 0 || maxPolicyVersion < 0) {
            throw new IllegalArgumentException("invalid capacity sample");
        }
    }
}
