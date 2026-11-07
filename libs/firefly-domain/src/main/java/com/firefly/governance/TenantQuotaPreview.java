package com.firefly.governance;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Explainable result of evaluating a quota proposal without mutating runtime state. */
public record TenantQuotaPreview(
        String tenantId,
        Instant evaluatedAt,
        long baseVersion,
        Instant windowFrom,
        Instant windowTo,
        double coverageRatio,
        TenantCapacityRisk overallRisk,
        List<FieldChange> changes,
        List<ResourcePreview> resources,
        List<String> warnings
) {
    public TenantQuotaPreview {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(windowFrom, "windowFrom");
        Objects.requireNonNull(windowTo, "windowTo");
        Objects.requireNonNull(overallRisk, "overallRisk");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public record FieldChange(String field, String before, String after) {
        public FieldChange {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }
    }

    public record ResourcePreview(
            TenantCapacityResource resource,
            long current,
            double average,
            long peak,
            long proposedLimit,
            Long recommendedLimit,
            double saturationRatio,
            long rejections,
            TenantCapacityQuality quality,
            TenantCapacityRisk risk,
            List<String> reasons
    ) {
        public ResourcePreview {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(quality, "quality");
            Objects.requireNonNull(risk, "risk");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        }
    }
}
