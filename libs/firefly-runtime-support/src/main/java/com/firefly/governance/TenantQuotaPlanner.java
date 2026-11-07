package com.firefly.governance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure deterministic quota preview calculator. */
public final class TenantQuotaPlanner {
    public TenantQuotaPreview preview(
            TenantDefinition policy,
            TenantUsage usage,
            TenantCapacityBaseline baseline,
            TenantQuotaProposal proposal,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (!policy.tenantId().equals(usage.tenantId()) || !policy.tenantId().equals(baseline.tenantId())) {
            throw new IllegalArgumentException("policy, usage and baseline tenantId must match");
        }
        if (proposal.expectedVersion() != policy.version()) {
            throw new IllegalStateException("tenant version conflict: expected " + proposal.expectedVersion()
                    + " but was " + policy.version());
        }

        List<TenantQuotaPreview.ResourcePreview> resources = new ArrayList<>();
        TenantCapacityRisk overall = TenantCapacityRisk.LOW;
        for (TenantCapacityResource resource : TenantCapacityResource.values()) {
            TenantCapacityBaseline.ResourceBaseline evidence = baseline.resource(resource);
            long current = current(usage, resource);
            long currentLimit = limit(policy, resource);
            long proposedLimit = proposal.proposedLimit(resource);
            TenantCapacityQuality quality = quality(baseline, evidence);
            Long recommendation = recommendation(current, currentLimit, evidence.peak(), quality,
                    proposal.headroomPercent());
            List<String> reasons = new ArrayList<>();
            TenantCapacityRisk risk;
            if (proposedLimit > 0 && current > proposedLimit) {
                risk = TenantCapacityRisk.CRITICAL;
                reasons.add("CURRENT_USAGE_ABOVE_PROPOSED_LIMIT");
            } else if (proposedLimit > 0 && evidence.peak() > proposedLimit) {
                risk = TenantCapacityRisk.HIGH;
                reasons.add("PROPOSED_LIMIT_BELOW_HISTORICAL_PEAK");
            } else if (quality != TenantCapacityQuality.COMPLETE) {
                risk = TenantCapacityRisk.UNKNOWN;
                reasons.add(quality.name());
            } else if (recommendation != null && proposedLimit > 0 && proposedLimit < recommendation) {
                risk = TenantCapacityRisk.MEDIUM;
                reasons.add("PROPOSED_LIMIT_BELOW_RECOMMENDATION");
            } else {
                risk = TenantCapacityRisk.LOW;
                reasons.add("PROPOSED_LIMIT_HAS_OBSERVED_HEADROOM");
            }
            if (evidence.rejections() > 0 && proposedLimit <= currentLimit) {
                risk = TenantCapacityRisk.max(risk, TenantCapacityRisk.HIGH);
                reasons.add("HISTORICAL_QUOTA_REJECTIONS");
            }
            resources.add(new TenantQuotaPreview.ResourcePreview(
                    resource, current, evidence.average(), evidence.peak(), proposedLimit, recommendation,
                    evidence.saturationRatio(), evidence.rejections(), quality, risk, reasons
            ));
            overall = TenantCapacityRisk.max(overall, risk);
        }

        List<String> warnings = new ArrayList<>();
        if (!proposal.enabled() && (usage.activeExecutions() > 0 || usage.backlog() > 0)) {
            overall = TenantCapacityRisk.CRITICAL;
            warnings.add("DISABLING_TENANT_WITH_ACTIVE_WORKLOAD_DOES_NOT_PREEMPT_RUNNING_WORK");
        }
        if (baseline.coverageRatio() < 0.8) warnings.add("CAPACITY_COVERAGE_BELOW_80_PERCENT");
        if (baseline.minPolicyVersion() != baseline.maxPolicyVersion()) warnings.add("MIXED_POLICY");
        if (baseline.mixedRuntimeVersion()) warnings.add("MIXED_VERSION");

        return new TenantQuotaPreview(policy.tenantId(), evaluatedAt, policy.version(),
                baseline.from(), baseline.to(), baseline.coverageRatio(), overall,
                changes(policy, proposal), resources, warnings);
    }

    private static TenantCapacityQuality quality(
            TenantCapacityBaseline baseline, TenantCapacityBaseline.ResourceBaseline resource
    ) {
        if (baseline.coverageRatio() < 0.8 || baseline.observedBuckets() == 0) {
            return TenantCapacityQuality.INSUFFICIENT_DATA;
        }
        if (baseline.mixedRuntimeVersion()) return TenantCapacityQuality.MIXED_VERSION;
        if (baseline.minPolicyVersion() != baseline.maxPolicyVersion()) return TenantCapacityQuality.MIXED_POLICY;
        if (resource.saturationRatio() > 0 || resource.rejections() > 0) {
            return TenantCapacityQuality.CENSORED_BY_QUOTA;
        }
        return TenantCapacityQuality.COMPLETE;
    }

    private static Long recommendation(
            long current, long currentLimit, long peak, TenantCapacityQuality quality, int headroomPercent
    ) {
        if (quality == TenantCapacityQuality.INSUFFICIENT_DATA
                || quality == TenantCapacityQuality.MIXED_POLICY
                || quality == TenantCapacityQuality.MIXED_VERSION) return null;
        long base = Math.max(current, peak);
        if (base == 0) return null;
        long calculated = ceilingWithHeadroom(base, headroomPercent);
        if (quality == TenantCapacityQuality.CENSORED_BY_QUOTA && currentLimit > 0) {
            return Math.max(currentLimit, calculated);
        }
        return calculated;
    }

    private static long ceilingWithHeadroom(long base, int percent) {
        try {
            long numerator = Math.multiplyExact(base, 100L + percent);
            return Math.addExact(numerator, 99) / 100;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long current(TenantUsage usage, TenantCapacityResource resource) {
        return switch (resource) {
            case CONCURRENCY -> usage.activeExecutions();
            case CPU_MILLIS -> usage.reservedCpuMillis();
            case MEMORY_BYTES -> usage.reservedMemoryBytes();
            case BACKLOG -> usage.backlog();
            case TRIGGER_RATE -> usage.triggersInWindow();
        };
    }

    private static long limit(TenantDefinition definition, TenantCapacityResource resource) {
        return switch (resource) {
            case CONCURRENCY -> definition.maxConcurrent();
            case CPU_MILLIS -> definition.maxCpuMillis();
            case MEMORY_BYTES -> definition.maxMemoryBytes();
            case BACKLOG -> definition.maxBacklog();
            case TRIGGER_RATE -> definition.maxTriggerRatePerSecond();
        };
    }

    private static List<TenantQuotaPreview.FieldChange> changes(
            TenantDefinition policy, TenantQuotaProposal proposal
    ) {
        List<TenantQuotaPreview.FieldChange> result = new ArrayList<>();
        add(result, "enabled", policy.enabled(), proposal.enabled());
        add(result, "weight", policy.weight(), proposal.weight());
        add(result, "maxConcurrent", policy.maxConcurrent(), proposal.maxConcurrent());
        add(result, "maxCpuMillis", policy.maxCpuMillis(), proposal.maxCpuMillis());
        add(result, "maxMemoryBytes", policy.maxMemoryBytes(), proposal.maxMemoryBytes());
        add(result, "maxBacklog", policy.maxBacklog(), proposal.maxBacklog());
        add(result, "maxTriggerRatePerSecond", policy.maxTriggerRatePerSecond(),
                proposal.maxTriggerRatePerSecond());
        return List.copyOf(result);
    }

    private static void add(List<TenantQuotaPreview.FieldChange> result, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            result.add(new TenantQuotaPreview.FieldChange(field, String.valueOf(before), String.valueOf(after)));
        }
    }
}
