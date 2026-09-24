package com.firefly.batch;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void marksLateEstimatedCompletionAsRiskWithoutClaimingAnObservedBreach() {
        SlaBudget budget = new SlaBudget(BASE, BASE.plusSeconds(10), BASE.plusSeconds(20),
                BASE.plusSeconds(60), Duration.ofSeconds(5));
        SlaBudgetAssessment assessment = budget.assess(BASE.plusSeconds(25), BASE.plusSeconds(5),
                BASE.plusSeconds(15), BASE.plusSeconds(70));
        assertFalse(assessment.breached());
        assertTrue(assessment.atRisk());
        assertTrue(assessment.recommendPriorityEscalation());
    }

    @Test
    void usesObservedCompletionInsteadOfAssessmentTimeForCompletedWork() {
        SlaBudget budget = new SlaBudget(BASE, BASE.plusSeconds(10), BASE.plusSeconds(20),
                BASE.plusSeconds(60), Duration.ofSeconds(5));

        SlaBudgetAssessment onTime = budget.assess(BASE.plusSeconds(120), BASE.plusSeconds(5),
                BASE.plusSeconds(15), BASE.plusSeconds(55), null);
        SlaBudgetAssessment late = budget.assess(BASE.plusSeconds(120), BASE.plusSeconds(5),
                BASE.plusSeconds(15), BASE.plusSeconds(65), null);

        assertFalse(onTime.breached());
        assertFalse(onTime.atRisk());
        assertFalse(onTime.recommendPriorityEscalation());
        assertTrue(late.breached());
    }

    @Test
    void rejectsImpossibleObservedPhaseOrdering() {
        SlaBudget budget = new SlaBudget(BASE, BASE.plusSeconds(10), BASE.plusSeconds(20),
                BASE.plusSeconds(60), Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class,
                () -> budget.assess(BASE.plusSeconds(30), null, BASE.plusSeconds(15), null, null));
    }
}
