package com.firefly.execution;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Plans and submits a replay while preserving the original root correlation.
 * Definition inputs are explicit because historical execution rows do not
 * contain arbitrary job parameters by design.
 */
public final class ExecutionReplayService {
    public static final String REPLAY_SOURCE_PARAMETER = "firefly.replay.sourceExecutionId";
    public static final String FAILED_TARGETS_ONLY_PARAMETER = "firefly.replay.failedTargetsOnly";
    public static final String FAILED_TARGET_IDS_PARAMETER = "firefly.replay.failedTargetIds";

    private final ExecutionRepository executions;
    private final Clock clock;

    public ExecutionReplayService(ExecutionRepository executions, Clock clock) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ExecutionReplayPlan plan(ExecutionReplayRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionRecord source = executions.findExecution(request.sourceExecutionId())
                .orElseThrow(() -> new IllegalArgumentException("execution not found: " + request.sourceExecutionId()));
        List<String> differences = differences(request.originalSnapshot(), request.currentSnapshot());
        String replayId = source.rootExecutionId() + "@replay:" + source.executionId();
        JobDefinition definition = replayDefinition(request);
        ExecutionCommand command = new ExecutionCommand(replayId, source.rootExecutionId(), source.runAttempt() + 1,
                definition, source.scheduledFireTime(), clock.instant(), source.ownerNodeId(), source.fencingToken());
        return new ExecutionReplayPlan(source.executionId(), source.rootExecutionId(), replayId, request.dryRun(),
                !differences.isEmpty(), request.failedTargetsOnly(), differences, command);
    }

    public boolean submit(ExecutionReplayPlan plan, boolean confirmation, Consumer<ExecutionCommand> submitter) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(submitter, "submitter");
        ExecutionCommand command = plan.commandIfExecutable(confirmation).orElse(null);
        if (command == null) return false;
        submitter.accept(command);
        return true;
    }

    private JobDefinition replayDefinition(ExecutionReplayRequest request) {
        Map<String, String> parameters = new HashMap<>(request.currentDefinition().parameters());
        parameters.put(REPLAY_SOURCE_PARAMETER, request.sourceExecutionId());
        parameters.put(FAILED_TARGETS_ONLY_PARAMETER, Boolean.toString(request.failedTargetsOnly()));
        if (!request.failedTargetIds().isEmpty()) {
            parameters.put(FAILED_TARGET_IDS_PARAMETER, String.join(",", request.failedTargetIds()));
        }
        JobDefinition current = request.currentDefinition();
        return new JobDefinition(current.id(), current.groupId(), current.name(), current.handlerName(),
                current.schedule(), current.zoneId(), current.misfirePolicy(), current.misfireGrace(),
                current.concurrencyPolicy(), current.maxCatchUpCount(), current.timeout(), parameters,
                current.destination(), current.retryPolicy(), current.dispatchMode(), current.routingStrategy(),
                current.completionPolicy(), current.shardCount(), current.routingKey(), current.retryScope(),
                current.enabled(), current.calendarId(), current.blackoutWindows(), current.dependencies());
    }

    private static List<String> differences(ReplayDefinitionSnapshot original, ReplayDefinitionSnapshot current) {
        List<String> differences = new ArrayList<>();
        if (original.definitionRevision() != current.definitionRevision()) differences.add("definitionRevision");
        if (original.calendarRevision() != current.calendarRevision()) differences.add("calendarRevision");
        if (original.dependencyRevision() != current.dependencyRevision()) differences.add("dependencyRevision");
        if (!original.parameters().equals(current.parameters())) {
            original.parameters().keySet().stream().filter(key -> !Objects.equals(original.parameters().get(key), current.parameters().get(key)))
                    .forEach(key -> differences.add("parameter:" + key));
            current.parameters().keySet().stream().filter(key -> !original.parameters().containsKey(key))
                    .forEach(key -> differences.add("parameter:" + key));
        }
        return differences.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }
}
