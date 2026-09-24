package com.firefly.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Explicit scheduling, startup, and completion budget for a batch execution. */
public record SlaBudget(Instant scheduledAt, Instant dispatchDeadline, Instant startDeadline,
                        Instant completionDeadline, Duration riskThreshold) {
    public SlaBudget {
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(dispatchDeadline, "dispatchDeadline");
        Objects.requireNonNull(startDeadline, "startDeadline");
        Objects.requireNonNull(completionDeadline, "completionDeadline");
        Objects.requireNonNull(riskThreshold, "riskThreshold");
        if (dispatchDeadline.isBefore(scheduledAt) || startDeadline.isBefore(dispatchDeadline)
                || completionDeadline.isBefore(startDeadline) || riskThreshold.isNegative()) {
            throw new IllegalArgumentException("SLA deadlines must be ordered and riskThreshold must not be negative");
        }
    }

    /**
     * Assesses an active execution. The fourth argument is a projection, not an
     * observed completion, so a late estimate is reported as risk rather than
     * an already breached SLA.
     */
    public SlaBudgetAssessment assess(
            Instant now, Instant dispatchedAt, Instant startedAt, Instant estimatedCompletionAt
    ) {
        return assess(now, dispatchedAt, startedAt, null, estimatedCompletionAt);
    }

    /** Assesses an execution using observed phase timestamps and an optional completion projection. */
    public SlaBudgetAssessment assess(
            Instant now,
            Instant dispatchedAt,
            Instant startedAt,
            Instant completedAt,
            Instant estimatedCompletionAt
    ) {
        Objects.requireNonNull(now, "now");
        validateObservedOrder(dispatchedAt, startedAt, completedAt);
        SlaPhase phase = startedAt != null ? SlaPhase.COMPLETION
                : dispatchedAt != null ? SlaPhase.STARTUP : SlaPhase.DISPATCH;
        Instant budgetEnd = phase == SlaPhase.DISPATCH ? dispatchDeadline
                : phase == SlaPhase.STARTUP ? startDeadline : completionDeadline;
        Duration remaining = now.isBefore(budgetEnd) ? Duration.between(now, budgetEnd) : Duration.ZERO;
        boolean dispatchBreached = dispatchedAt == null
                ? phase == SlaPhase.DISPATCH && now.isAfter(dispatchDeadline)
                : dispatchedAt.isAfter(dispatchDeadline);
        boolean startBreached = startedAt == null
                ? phase == SlaPhase.STARTUP && now.isAfter(startDeadline)
                : startedAt.isAfter(startDeadline);
        boolean completionBreached = completedAt == null
                ? phase == SlaPhase.COMPLETION && now.isAfter(completionDeadline)
                : completedAt.isAfter(completionDeadline);
        boolean breached = dispatchBreached || startBreached || completionBreached;
        boolean projectedLate = completedAt == null
                && estimatedCompletionAt != null
                && estimatedCompletionAt.isAfter(completionDeadline);
        boolean nearingDeadline = completedAt == null && remaining.compareTo(riskThreshold) <= 0;
        boolean atRisk = breached || projectedLate || nearingDeadline;
        boolean recommendEscalation = completedAt == null && atRisk && !breached;
        return new SlaBudgetAssessment(phase, remaining, atRisk, breached, recommendEscalation);
    }

    private void validateObservedOrder(Instant dispatchedAt, Instant startedAt, Instant completedAt) {
        if (startedAt != null && dispatchedAt == null) {
            throw new IllegalArgumentException("startedAt requires dispatchedAt");
        }
        if (completedAt != null && startedAt == null) {
            throw new IllegalArgumentException("completedAt requires startedAt");
        }
        if (dispatchedAt != null && dispatchedAt.isBefore(scheduledAt)) {
            throw new IllegalArgumentException("dispatchedAt must not be before scheduledAt");
        }
        if (startedAt != null && startedAt.isBefore(dispatchedAt)) {
            throw new IllegalArgumentException("startedAt must not be before dispatchedAt");
        }
        if (completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }
}
