package com.firefly.operations;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExecutionOperationsTest {
    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");

    @Test
    void reconstructsTimelineInTimeOrderFromCurrentSnapshots() {
        InMemoryExecutionRepository repository = new InMemoryExecutionRepository();
        repository.saveExecution(new ExecutionRecord(
                "execution-1", "root-1", 0, "job-1",
                NOW.minusSeconds(30), NOW.minusSeconds(20), ExecutorDispatchMode.UNICAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.RUNNING, 1, 1,
                "scheduler-a", 4, NOW.plusSeconds(30), NOW.minusSeconds(30), NOW
        ));
        repository.saveTargets(List.of(new ExecutionTargetRecord(
                "target-1", "execution-1", "instance-a", "gateway-a", null,
                ExecutionStatus.RUNNING, 1, NOW.minusSeconds(10), null, "", NOW.minusSeconds(30), NOW
        )));

        List<ExecutionTimelineEvent> events = new ExecutionTimelineService(repository).timeline("execution-1");

        assertEquals(List.of(
                ExecutionTimelineEvent.Type.SCHEDULED,
                ExecutionTimelineEvent.Type.DISPATCHED,
                ExecutionTimelineEvent.Type.ACKNOWLEDGED,
                ExecutionTimelineEvent.Type.EXECUTION_STATUS,
                ExecutionTimelineEvent.Type.DEADLINE
        ), events.stream().map(ExecutionTimelineEvent::type).toList());
        assertEquals(ExecutionTimelineEvent.Source.TARGET_SNAPSHOT, events.get(2).source());
    }

    @Test
    void raisesTimeoutAndConsecutiveFailureAlertsWithStableFingerprint() {
        ExecutionRecord timeout = execution("attempt-2", "root-1", 2, ExecutionStatus.TIMEOUT, NOW);
        AlertRule timeoutRule = new AlertRule(
                "timeout-rule", "job-1", AlertType.TIMEOUT, 1,
                Duration.ofMinutes(5), Duration.ZERO, AlertSeverity.CRITICAL, true
        );
        AlertEvent timeoutAlert = new AlertRuleEvaluator().evaluate(timeoutRule, List.of(timeout), NOW);
        assertEquals("timeout-rule:root-1", timeoutAlert.fingerprint());

        AlertRule failureRule = new AlertRule(
                "failure-rule", "job-1", AlertType.CONSECUTIVE_FAILURE, 2,
                Duration.ofMinutes(5), Duration.ZERO, AlertSeverity.WARNING, true
        );
        AlertEvent failureAlert = new AlertRuleEvaluator().evaluate(failureRule, List.of(
                execution("attempt-2", "root-1", 2, ExecutionStatus.FAILED, NOW),
                execution("attempt-1", "root-1", 1, ExecutionStatus.FAILED, NOW.minusSeconds(1)),
                execution("attempt-0", "root-1", 0, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(2))
        ), NOW);
        assertEquals(2, failureAlert.observedValue());
        assertNull(new AlertRuleEvaluator().evaluate(failureRule, List.of(
                execution("attempt-2", "root-1", 2, ExecutionStatus.FAILED, NOW),
                execution("attempt-1", "root-1", 1, ExecutionStatus.SUCCEEDED, NOW.minusSeconds(1))
        ), NOW));
    }

    private ExecutionRecord execution(
            String executionId, String rootExecutionId, int attempt, ExecutionStatus status, Instant updatedAt
    ) {
        return new ExecutionRecord(
                executionId, rootExecutionId, attempt, "job-1", updatedAt, updatedAt,
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS, status,
                1, 1, "scheduler-a", attempt + 1L, updatedAt, updatedAt
        );
    }
}
