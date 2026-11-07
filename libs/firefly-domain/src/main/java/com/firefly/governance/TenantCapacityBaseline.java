package com.firefly.governance;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Bounded historical evidence computed by a capacity store. */
public record TenantCapacityBaseline(
        String tenantId,
        Instant from,
        Instant to,
        int observedBuckets,
        int expectedBuckets,
        double coverageRatio,
        long minPolicyVersion,
        long maxPolicyVersion,
        boolean mixedRuntimeVersion,
        List<ResourceBaseline> resources
) {
    public TenantCapacityBaseline {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        if (observedBuckets < 0 || expectedBuckets < 0 || coverageRatio < 0 || coverageRatio > 1) {
            throw new IllegalArgumentException("invalid baseline coverage");
        }
    }

    public ResourceBaseline resource(TenantCapacityResource resource) {
        return resources.stream().filter(value -> value.resource() == resource).findFirst()
                .orElse(new ResourceBaseline(resource, 0, 0, 0, 0));
    }

    public record ResourceBaseline(
            TenantCapacityResource resource,
            double average,
            long peak,
            double saturationRatio,
            long rejections
    ) {
        public ResourceBaseline {
            Objects.requireNonNull(resource, "resource");
            if (average < 0 || peak < 0 || saturationRatio < 0 || saturationRatio > 1 || rejections < 0) {
                throw new IllegalArgumentException("invalid resource baseline");
            }
        }
    }

    public static TenantCapacityBaseline empty(String tenantId, Instant from, Instant to, int expectedBuckets) {
        return new TenantCapacityBaseline(tenantId, from, to, 0, expectedBuckets, 0, 0, 0, false,
                java.util.Arrays.stream(TenantCapacityResource.values())
                        .map(resource -> new ResourceBaseline(resource, 0, 0, 0, 0)).toList());
    }
}
