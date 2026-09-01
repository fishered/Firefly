package com.firefly.operations;

import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.ExecutionTargetRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Rebuilds an operator timeline without inventing unavailable historical transitions. */
public final class ExecutionTimelineService {
    private static final Comparator<ExecutionTimelineEvent> ORDER = Comparator
            .comparing(ExecutionTimelineEvent::occurredAt)
            .thenComparing(event -> event.type().ordinal())
            .thenComparing(ExecutionTimelineEvent::eventId);

    private final ExecutionRepository executions;

    public ExecutionTimelineService(ExecutionRepository executions) {
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    public List<ExecutionTimelineEvent> timeline(String executionId) {
        ExecutionRecord execution = executions.findExecution(executionId).orElse(null);
        if (execution == null) return List.of();

        List<ExecutionTimelineEvent> events = new ArrayList<>();
        events.add(new ExecutionTimelineEvent(
                execution.executionId() + ":scheduled",
                execution.executionId(), "",
                ExecutionTimelineEvent.Type.SCHEDULED,
                execution.scheduledFireTime(), null, "scheduled fire time",
                ExecutionTimelineEvent.Source.EXECUTION_SNAPSHOT
        ));
        events.add(new ExecutionTimelineEvent(
                execution.executionId() + ":dispatched",
                execution.executionId(), "",
                ExecutionTimelineEvent.Type.DISPATCHED,
                execution.dispatchTime(), null, "dispatch accepted",
                ExecutionTimelineEvent.Source.EXECUTION_SNAPSHOT
        ));
        events.add(new ExecutionTimelineEvent(
                execution.executionId() + ":status",
                execution.executionId(), "",
                ExecutionTimelineEvent.Type.EXECUTION_STATUS,
                execution.updatedAt(), execution.status(), "current execution snapshot",
                ExecutionTimelineEvent.Source.EXECUTION_SNAPSHOT
        ));
        if (execution.timeoutAt() != null) {
            events.add(new ExecutionTimelineEvent(
                    execution.executionId() + ":deadline",
                    execution.executionId(), "",
                    ExecutionTimelineEvent.Type.DEADLINE,
                    execution.timeoutAt(), null, "execution deadline",
                    ExecutionTimelineEvent.Source.EXECUTION_SNAPSHOT
            ));
        }

        for (ExecutionTargetRecord target : executions.listTargets(executionId)) {
            if (target.acknowledgedAt() != null) {
                events.add(new ExecutionTimelineEvent(
                        target.targetExecutionId() + ":ack",
                        execution.executionId(), target.targetExecutionId(),
                        ExecutionTimelineEvent.Type.ACKNOWLEDGED,
                        target.acknowledgedAt(), target.status(), "target acknowledged",
                        ExecutionTimelineEvent.Source.TARGET_SNAPSHOT
                ));
            }
            if (target.completedAt() != null) {
                events.add(new ExecutionTimelineEvent(
                        target.targetExecutionId() + ":completed",
                        execution.executionId(), target.targetExecutionId(),
                        ExecutionTimelineEvent.Type.TARGET_COMPLETED,
                        target.completedAt(), target.status(), target.errorMessage(),
                        ExecutionTimelineEvent.Source.TARGET_SNAPSHOT
                ));
            }
        }
        return events.stream().sorted(ORDER).toList();
    }
}
