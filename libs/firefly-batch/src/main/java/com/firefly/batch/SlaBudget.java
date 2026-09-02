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

    public SlaBudgetAssessment assess(Instant now, Instant dispatchedAt, Instant startedAt, Instant estimatedCompletionAt) {
        Objects.requireNonNull(now, "now");
        boolean breached = !now.isBefore(completionDeadline)
                || dispatchedAt != null && dispatchedAt.isAfter(dispatchDeadline)
                || startedAt != null && startedAt.isAfter(startDeadline)
                || estimatedCompletionAt != null && estimatedCompletionAt.isAfter(completionDeadline);
        SlaPhase phase = startedAt != null ? SlaPhase.COMPLETION
                : dispatchedAt != null ? SlaPhase.STARTUP : SlaPhase.DISPATCH;
        Instant budgetEnd = phase == SlaPhase.DISPATCH ? dispatchDeadline
                : phase == SlaPhase.STARTUP ? startDeadline : completionDeadline;
        Duration remaining = now.isBefore(budgetEnd) ? Duration.between(now, budgetEnd) : Duration.ZERO;
        boolean atRisk = breached || remaining.compareTo(riskThreshold) <= 0;
        return new SlaBudgetAssessment(phase, remaining, atRisk, breached, atRisk && !breached);
    }
}
