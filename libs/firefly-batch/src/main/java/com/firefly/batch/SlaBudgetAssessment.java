package com.firefly.batch;

import java.time.Duration;
import java.util.Objects;

/** Decision data that can drive priority escalation and early operator alerts. */
public record SlaBudgetAssessment(SlaPhase phase, Duration remaining, boolean atRisk,
                                  boolean breached, boolean recommendPriorityEscalation) {
    public SlaBudgetAssessment {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(remaining, "remaining");
        if (remaining.isNegative()) throw new IllegalArgumentException("remaining must not be negative");
        if (breached && recommendPriorityEscalation) throw new IllegalArgumentException("breached budget cannot recommend escalation");
    }
}
