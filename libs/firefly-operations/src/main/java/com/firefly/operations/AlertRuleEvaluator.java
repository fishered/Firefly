package com.firefly.operations;

import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure evaluator; persistence and notification are deliberately outside the scheduler core. */
public final class AlertRuleEvaluator {
    public AlertEvent evaluate(AlertRule rule, List<ExecutionRecord> candidates, Instant now) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(now, "now");
        if (!rule.enabled()) return null;

        List<ExecutionRecord> executions = candidates.stream()
                .filter(execution -> rule.jobId().isBlank() || rule.jobId().equals(execution.jobId()))
                .filter(execution -> !execution.updatedAt().isBefore(now.minus(rule.window())))
                .sorted(Comparator.comparing(ExecutionRecord::updatedAt).reversed())
                .toList();
        if (executions.isEmpty()) return null;

        ExecutionRecord latest = executions.getFirst();
        return switch (rule.type()) {
            case TIMEOUT -> latest.status() == ExecutionStatus.TIMEOUT
                    ? event(rule, latest, 1, now, "execution timed out") : null;
            case CONSECUTIVE_FAILURE -> consecutiveFailure(rule, executions, now);
            case LATENCY -> latency(rule, executions, now);
        };
    }

    private AlertEvent consecutiveFailure(AlertRule rule, List<ExecutionRecord> executions, Instant now) {
        long failures = 0;
        ExecutionRecord latest = executions.getFirst();
        for (ExecutionRecord execution : executions) {
            if (execution.status() == ExecutionStatus.FAILED || execution.status() == ExecutionStatus.TIMEOUT) {
                failures++;
                continue;
            }
            break;
        }
        return failures >= rule.threshold()
                ? event(rule, latest, failures, now, "consecutive execution failures") : null;
    }

    private AlertEvent latency(AlertRule rule, List<ExecutionRecord> executions, Instant now) {
        for (ExecutionRecord execution : executions) {
            long seconds = Math.max(0, Duration.between(
                    execution.scheduledFireTime(), execution.dispatchTime()).toSeconds()
            );
            if (seconds >= rule.threshold()) {
                return event(rule, execution, seconds, now, "execution dispatch latency exceeded threshold");
            }
        }
        return null;
    }

    private AlertEvent event(
            AlertRule rule, ExecutionRecord execution, long observedValue, Instant observedAt, String message
    ) {
        return new AlertEvent(
                rule.ruleId() + ":" + execution.rootExecutionId(),
                rule.ruleId(), execution.jobId(), execution.executionId(), rule.type(), rule.severity(),
                observedValue, observedAt, message
        );
    }
}
