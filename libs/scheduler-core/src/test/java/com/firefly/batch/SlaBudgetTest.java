package com.firefly.batch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlaBudgetTest {
    private static final Instant BASE = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void exposesPhaseRemainingRiskAndEscalationRecommendation() {
        SlaBudget budget = new SlaBudget(BASE, BASE.plusSeconds(10), BASE.plusSeconds(20),
                BASE.plusSeconds(60), Duration.ofSeconds(5));
        SlaBudgetAssessment assessment = budget.assess(BASE.plusSeconds(7), null, null, null);
        assertEquals(SlaPhase.DISPATCH, assessment.phase());
        assertEquals(Duration.ofSeconds(3), assessment.remaining());
        assertTrue(assessment.atRisk());
        assertTrue(assessment.recommendPriorityEscalation());
    }

    @Test
    void marksLateEstimatedCompletionAsBreachedWithoutEscalation() {
        SlaBudget budget = new SlaBudget(BASE, BASE.plusSeconds(10), BASE.plusSeconds(20),
                BASE.plusSeconds(60), Duration.ofSeconds(5));
        SlaBudgetAssessment assessment = budget.assess(BASE.plusSeconds(25), BASE.plusSeconds(5),
                BASE.plusSeconds(15), BASE.plusSeconds(70));
        assertTrue(assessment.breached());
        assertTrue(assessment.atRisk());
        assertTrue(!assessment.recommendPriorityEscalation());
    }
}
