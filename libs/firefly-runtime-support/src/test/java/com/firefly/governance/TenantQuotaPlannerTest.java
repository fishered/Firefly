package com.firefly.governance;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantQuotaPlannerTest {
    private static final Instant NOW = Instant.parse("2026-11-07T08:00:00Z");

    @Test
    void returnsCriticalWithoutMutatingWhenProposalIsBelowCurrentUsage() {
        TenantDefinition policy = policy(4, 32);
        TenantUsage usage = new TenantUsage("payments", 18, 8_000, 16_000, 30, NOW, 12, 9);
        TenantCapacityBaseline baseline = completeBaseline(4, 12, 35);
        TenantQuotaProposal proposal = proposal(4, 10, 20);

        TenantQuotaPreview result = new TenantQuotaPlanner().preview(policy, usage, baseline, proposal, NOW);

        assertEquals(TenantCapacityRisk.CRITICAL, result.overallRisk());
        assertEquals(TenantCapacityRisk.CRITICAL, result.resources().getFirst().risk());
        assertTrue(result.resources().getFirst().reasons().contains("CURRENT_USAGE_ABOVE_PROPOSED_LIMIT"));
        assertEquals(4, policy.version());
        assertEquals(9, usage.version());
    }

    @Test
    void recommendationIsDeterministicAndCeiled() {
        TenantQuotaPreview result = new TenantQuotaPlanner().preview(
                policy(4, 50),
                new TenantUsage("payments", 18, 0, 0, 0, NOW, 0, 1),
                completeBaseline(4, 12, 35), proposal(4, 50, 20), NOW
        );

        assertEquals(42L, result.resources().getFirst().recommendedLimit());
        assertEquals(TenantCapacityRisk.LOW, result.resources().getFirst().risk());
    }

    @Test
    void refusesPreciseRecommendationForInsufficientOrMixedEvidence() {
        TenantCapacityBaseline insufficient = new TenantCapacityBaseline(
                "payments", NOW.minus(Duration.ofDays(7)), NOW, 10, 2016, 10d / 2016d,
                3, 4, false, resources(12, 35, 0, 0)
        );
        TenantQuotaPreview result = new TenantQuotaPlanner().preview(
                policy(4, 50), TenantUsage.empty("payments", NOW), insufficient,
                proposal(4, 50, 20), NOW
        );

        assertNull(result.resources().getFirst().recommendedLimit());
        assertEquals(TenantCapacityQuality.INSUFFICIENT_DATA, result.resources().getFirst().quality());
        assertEquals(TenantCapacityRisk.UNKNOWN, result.resources().getFirst().risk());
    }

    @Test
    void detectsPreviewVersionConflict() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                new TenantQuotaPlanner().preview(policy(5, 50), TenantUsage.empty("payments", NOW),
                        completeBaseline(5, 0, 0), proposal(4, 50, 20), NOW));
        assertTrue(failure.getMessage().contains("version conflict"));
    }

    private static TenantDefinition policy(long version, int concurrent) {
        return new TenantDefinition("payments", true, 3, concurrent, 20_000, 40_000,
                100, 200, version, NOW);
    }

    private static TenantQuotaProposal proposal(long version, int concurrent, int headroom) {
        return new TenantQuotaProposal(version, true, 3, concurrent, 20_000, 40_000,
                100, 200, Duration.ofDays(7), headroom);
    }

    private static TenantCapacityBaseline completeBaseline(long policyVersion, double average, long peak) {
        return new TenantCapacityBaseline("payments", NOW.minus(Duration.ofDays(7)), NOW,
                2016, 2016, 1, policyVersion, policyVersion, false,
                resources(average, peak, 0, 0));
    }

    private static List<TenantCapacityBaseline.ResourceBaseline> resources(
            double average, long peak, double saturation, long rejections
    ) {
        return java.util.Arrays.stream(TenantCapacityResource.values())
                .map(resource -> new TenantCapacityBaseline.ResourceBaseline(
                        resource, resource == TenantCapacityResource.CONCURRENCY ? average : 0,
                        resource == TenantCapacityResource.CONCURRENCY ? peak : 0,
                        resource == TenantCapacityResource.CONCURRENCY ? saturation : 0,
                        resource == TenantCapacityResource.CONCURRENCY ? rejections : 0))
                .toList();
    }
}
