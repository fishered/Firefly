package com.firefly.executor.netty;

import com.firefly.catalog.SchedulerCatalog;
import com.firefly.domain.ExecutionContext;
import com.firefly.domain.ExecutorRetryScope;
import com.firefly.executor.ExecutorInstanceDirectory;
import com.firefly.executor.ExecutorInstanceLocation;
import com.firefly.executor.RemoteDispatchRequest;
import com.firefly.executor.RemoteDispatchResult;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.tracing.FireflyTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Plans, persists, and delivers executor dispatches. */
final class NettyExecutorDispatchService {
    private final SchedulerCatalog schedulerCatalog;
    private final NettyExecutorConnectionRegistry connectionRegistry;
    private final ExecutorInstanceDirectory instanceDirectory;
    private final Clock clock;
    private final ExecutionRepository executionRepository;
    private final String gatewayNodeId;
    private final NettyGatewayForwardingTransport forwardingTransport;
    private final NettyExecutorJsonCodec codec;
    private final ConcurrentHashMap<String, AtomicInteger> routingCursors = new ConcurrentHashMap<>();

    NettyExecutorDispatchService(
            SchedulerCatalog schedulerCatalog,
            NettyExecutorConnectionRegistry connectionRegistry,
            ExecutorInstanceDirectory instanceDirectory,
            Clock clock,
            ExecutionRepository executionRepository,
            String gatewayNodeId,
            NettyGatewayForwardingTransport forwardingTransport,
            NettyExecutorJsonCodec codec
    ) {
        this.schedulerCatalog = schedulerCatalog;
        this.connectionRegistry = connectionRegistry;
        this.instanceDirectory = instanceDirectory;
        this.clock = clock;
        this.executionRepository = executionRepository;
        this.gatewayNodeId = gatewayNodeId;
        this.forwardingTransport = forwardingTransport;
        this.codec = codec;
    }

    RemoteDispatchResult dispatch(RemoteDispatchRequest request) {
        Span span = FireflyTelemetry.tracer().spanBuilder("firefly.gateway.dispatch")
                .setParent(FireflyTelemetry.extract(request.traceCarrier()))
                .setSpanKind(SpanKind.PRODUCER)
                .setAttribute("firefly.phase", "gateway")
                .setAttribute("firefly.execution.id", request.context().executionId())
                .setAttribute("firefly.job.id", request.context().jobId())
                .setAttribute("firefly.executor.name", request.executorName())
                .setAttribute("firefly.node.id", gatewayNodeId)
                .setAttribute("firefly.run.attempt", request.runAttempt())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (schedulerCatalog.findExecutor(request.executorName())
                    .map(definition -> !definition.enabled()).orElse(false)) {
                span.setStatus(StatusCode.ERROR, "executor disabled");
                return RemoteDispatchResult.unavailable();
            }
            RemoteDispatchResult result = switch (request.dispatchMode()) {
                case UNICAST -> dispatchUnicast(request);
                case BROADCAST -> dispatchBroadcast(request);
                case SHARDING -> dispatchShards(request);
            };
            span.setAttribute("firefly.gateway.requested-targets", result.requestedTargets());
            span.setAttribute("firefly.gateway.accepted-targets", result.acceptedTargets());
            if (!result.accepted()) span.setStatus(StatusCode.ERROR, "no executor accepted dispatch");
            return result;
        } catch (RuntimeException failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }

    boolean hasRoute(String executorName) {
        return !connectionRegistry.list(executorName).isEmpty()
                || !instanceDirectory.listOnline(executorName, clock.instant()).isEmpty();
    }

    private RemoteDispatchResult dispatchUnicast(RemoteDispatchRequest request) {
        var sharedTarget = selectSharedLocation(
                request.executorName(), request.routingStrategy(), request.routingKey()
        );
        if (sharedTarget.isPresent()) {
            return dispatchDirectoryPlans(request, 1, List.of(new DirectoryTargetPlan(
                    sharedTarget.get(), request.context().executionId(), null, null
            )), List.of());
        }
        var target = connectionRegistry.select(
                request.executorName(), request.routingStrategy(), request.routingKey()
        );
        if (target.isEmpty()) {
            saveDispatch(request, 1, List.of());
            return new RemoteDispatchResult(1, 0, List.of());
        }
        return dispatchPlans(request, 1, List.of(new TargetPlan(
                target.get(), request.context().executionId(), null, null
        )));
    }

    private RemoteDispatchResult dispatchBroadcast(RemoteDispatchRequest request) {
        List<ExecutorInstanceLocation> sharedLocations = onlineLocations(request.executorName());
        if (!sharedLocations.isEmpty()) return dispatchBroadcastShared(request, sharedLocations);
        List<ExecutionTargetRecord> existingTargets =
                executionRepository.listTargets(request.context().executionId());
        if (!existingTargets.isEmpty()) {
            List<TargetPlan> retryPlans = existingTargets.stream()
                    .map(existing -> connectionRegistry.find(request.executorName(), existing.instanceId())
                            .map(target -> new TargetPlan(
                                    target, existing.targetExecutionId(), existing.shardIndex(), null
                            )))
                    .flatMap(Optional::stream)
                    .toList();
            return dispatchPlans(request, existingTargets.size(), retryPlans);
        }
        Optional<List<ExecutionTargetRecord>> retryTargets = retrySourceTargets(request);
        if (retryTargets.isPresent()) {
            List<ExecutionTargetRecord> sourceTargets = retryTargets.get();
            List<ExecutionTargetRecord> requestedTargets = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                    ? sourceTargets
                    : sourceTargets.stream()
                            .filter(target -> target.status() != ExecutionStatus.SUCCEEDED)
                            .toList();
            List<TargetPlan> plans = requestedTargets.stream()
                    .map(existing -> connectionRegistry.find(request.executorName(), existing.instanceId())
                            .map(target -> new TargetPlan(
                                    target,
                                    request.context().executionId() + "@instance:" + existing.instanceId(),
                                    null,
                                    null
                            )))
                    .flatMap(Optional::stream)
                    .toList();
            int parentExpectedTargets = retrySourceExecution(request)
                    .map(ExecutionRecord::expectedTargets)
                    .orElse(sourceTargets.size());
            return dispatchRetryPlans(
                    request, parentExpectedTargets, requestedTargets.size(), plans, sourceTargets
            );
        }
        List<NettyExecutorConnectionRegistry.ConnectionTarget> targets =
                connectionRegistry.list(request.executorName());
        List<TargetPlan> plans = targets.stream()
                .map(target -> new TargetPlan(
                        target,
                        request.context().executionId() + "@instance:" + target.instanceId(),
                        null,
                        null
                ))
                .toList();
        return dispatchPlans(request, targets.size(), plans);
    }

    private RemoteDispatchResult dispatchShards(RemoteDispatchRequest request) {
        List<ExecutorInstanceLocation> sharedLocations = onlineLocations(request.executorName());
        if (!sharedLocations.isEmpty()) return dispatchShardsShared(request, sharedLocations);
        List<ExecutionTargetRecord> sourceTargets = retrySourceTargets(request).orElse(List.of());
        Set<Integer> successfulShards = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                ? Set.of()
                : sourceTargets.stream()
                .filter(target -> target.status() == ExecutionStatus.SUCCEEDED)
                .map(ExecutionTargetRecord::shardIndex)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        ArrayList<TargetPlan> plans = new ArrayList<>();
        for (int shard = 0; shard < request.shardCount(); shard++) {
            int shardIndex = shard;
            if (successfulShards.contains(shardIndex)) continue;
            String shardKey = request.routingKey() + ":" + shard;
            connectionRegistry.select(request.executorName(), request.routingStrategy(), shardKey)
                    .ifPresent(target -> {
                        String childExecutionId = request.context().executionId() + "@shard:" + shardIndex;
                        plans.add(new TargetPlan(target, childExecutionId, shardIndex, request.shardCount()));
                    });
        }
        int requestedTargets = request.shardCount() - successfulShards.size();
        if (request.runAttempt() > 0 && retrySourceExecution(request).isPresent()) {
            return dispatchRetryPlans(
                    request, request.shardCount(), requestedTargets, plans, sourceTargets
            );
        }
        return dispatchPlans(request, request.shardCount(), plans);
    }

    private Optional<List<ExecutionTargetRecord>> retrySourceTargets(RemoteDispatchRequest request) {
        return retrySourceExecution(request)
                .map(source -> executionRepository.listTargets(source.executionId()));
    }

    private Optional<ExecutionRecord> retrySourceExecution(RemoteDispatchRequest request) {
        if (request.runAttempt() <= 0) return Optional.empty();
        String sourceExecutionId = request.runAttempt() == 1
                ? request.rootExecutionId()
                : request.rootExecutionId() + "@attempt:" + (request.runAttempt() - 1);
        return executionRepository.findExecution(sourceExecutionId);
    }

    private RemoteDispatchResult dispatchBroadcastShared(
            RemoteDispatchRequest request, List<ExecutorInstanceLocation> locations
    ) {
        List<ExecutionTargetRecord> existingTargets =
                executionRepository.listTargets(request.context().executionId());
        if (!existingTargets.isEmpty()) {
            List<DirectoryTargetPlan> plans = existingTargets.stream()
                    .map(existing -> findLocation(locations, existing.instanceId())
                            .map(location -> new DirectoryTargetPlan(
                                    location, existing.targetExecutionId(), existing.shardIndex(), null
                            )))
                    .flatMap(Optional::stream)
                    .toList();
            return dispatchDirectoryPlans(request, existingTargets.size(), plans, List.of());
        }
        List<ExecutionTargetRecord> sourceTargets = retrySourceTargets(request).orElse(List.of());
        if (!sourceTargets.isEmpty()) {
            List<ExecutionTargetRecord> requested = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                    ? sourceTargets
                    : sourceTargets.stream().filter(target -> target.status() != ExecutionStatus.SUCCEEDED).toList();
            List<DirectoryTargetPlan> plans = requested.stream()
                    .map(existing -> findLocation(locations, existing.instanceId())
                            .map(location -> new DirectoryTargetPlan(
                                    location,
                                    request.context().executionId() + "@instance:" + existing.instanceId(),
                                    existing.shardIndex(), null
                            )))
                    .flatMap(Optional::stream)
                    .toList();
            List<ExecutionTargetRecord> carried = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                    ? List.of()
                    : sourceTargets.stream().filter(target -> target.status() == ExecutionStatus.SUCCEEDED).toList();
            int expected = retrySourceExecution(request).map(ExecutionRecord::expectedTargets)
                    .orElse(sourceTargets.size());
            return dispatchDirectoryPlans(request, expected, plans, carried, requested.size());
        }
        List<DirectoryTargetPlan> plans = locations.stream()
                .map(location -> new DirectoryTargetPlan(
                        location, request.context().executionId() + "@instance:" + location.instanceId(),
                        null, null
                ))
                .toList();
        return dispatchDirectoryPlans(request, locations.size(), plans, List.of());
    }

    private RemoteDispatchResult dispatchShardsShared(
            RemoteDispatchRequest request, List<ExecutorInstanceLocation> locations
    ) {
        List<ExecutionTargetRecord> sourceTargets = retrySourceTargets(request).orElse(List.of());
        Set<Integer> successfulShards = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                ? Set.of()
                : sourceTargets.stream()
                .filter(target -> target.status() == ExecutionStatus.SUCCEEDED)
                .map(ExecutionTargetRecord::shardIndex)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        ArrayList<DirectoryTargetPlan> plans = new ArrayList<>();
        for (int shard = 0; shard < request.shardCount(); shard++) {
            if (successfulShards.contains(shard)) continue;
            int shardIndex = shard;
            selectLocation(locations, request.routingStrategy(), request.routingKey() + ":" + shard)
                    .ifPresent(location -> plans.add(new DirectoryTargetPlan(
                            location, request.context().executionId() + "@shard:" + shardIndex,
                            shardIndex, request.shardCount()
                    )));
        }
        List<ExecutionTargetRecord> carried = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                ? List.of()
                : sourceTargets.stream().filter(target -> target.status() == ExecutionStatus.SUCCEEDED).toList();
        int requested = request.shardCount() - successfulShards.size();
        return dispatchDirectoryPlans(request, request.shardCount(), plans, carried, requested);
    }

    private RemoteDispatchResult dispatchDirectoryPlans(
            RemoteDispatchRequest request, int expectedTargets,
            List<DirectoryTargetPlan> plans,
            List<ExecutionTargetRecord> carriedTargets
    ) {
        return dispatchDirectoryPlans(request, expectedTargets, plans, carriedTargets, expectedTargets);
    }

    private RemoteDispatchResult dispatchDirectoryPlans(
            RemoteDispatchRequest request, int expectedTargets,
            List<DirectoryTargetPlan> plans,
            List<ExecutionTargetRecord> carriedTargets,
            int requestedTargets
    ) {
        saveDirectoryDispatch(request, expectedTargets, plans, carriedTargets);
        int accepted = 0;
        ArrayList<String> instances = new ArrayList<>();
        for (DirectoryTargetPlan plan : plans) {
            if (sendDirectory(plan, request)) {
                accepted++;
                instances.add(plan.location().instanceId());
            }
        }
        return new RemoteDispatchResult(requestedTargets, accepted, List.copyOf(instances));
    }

    private void saveDirectoryDispatch(
            RemoteDispatchRequest request, int expectedTargets,
            List<DirectoryTargetPlan> plans,
            List<ExecutionTargetRecord> carriedTargets
    ) {
        Instant now = clock.instant();
        executionRepository.saveExecution(new ExecutionRecord(
                request.context().executionId(), request.rootExecutionId(), request.runAttempt(),
                request.context().jobId(), request.context().scheduledFireTime(), request.context().dispatchTime(),
                request.dispatchMode(), request.completionPolicy(),
                plans.isEmpty() && carriedTargets.isEmpty() ? ExecutionStatus.FAILED : ExecutionStatus.DISPATCHED,
                expectedTargets, plans.size() + carriedTargets.size(), request.ownerNodeId(), request.fencingToken(),
                now, now
        ));
        List<ExecutionTargetRecord> planned = plans.stream().map(plan -> new ExecutionTargetRecord(
                plan.targetExecutionId(), request.context().executionId(), plan.location().instanceId(),
                plan.location().gatewayNodeId(), plan.shardIndex(), ExecutionStatus.DISPATCHED,
                1, null, null, "", now, now
        )).toList();
        List<ExecutionTargetRecord> carried = carriedTargets.stream().map(source ->
                new ExecutionTargetRecord(
                        carriedTargetExecutionId(request, source), request.context().executionId(),
                        source.instanceId(), source.gatewayNodeId(), source.shardIndex(), ExecutionStatus.SUCCEEDED,
                        source.attempt(), now, now, "", now, now
                )
        ).toList();
        executionRepository.saveTargets(Stream.concat(planned.stream(), carried.stream()).toList());
    }

    private boolean sendDirectory(DirectoryTargetPlan plan, RemoteDispatchRequest request) {
        String frame = codec.encode(triggerMessage(
                request, plan.targetExecutionId(), plan.location().instanceId(),
                plan.shardIndex(), plan.shardTotal()
        ));
        var local = connectionRegistry.find(request.executorName(), plan.location().instanceId())
                .filter(target -> target.sessionId().equals(plan.location().sessionId()));
        if (local.isPresent()) {
            local.get().channel().writeAndFlush(frame + "\n");
            return true;
        }
        return forwardingTransport.forward(
                plan.location().gatewayAddress(), request.executorName(), plan.location().instanceId(),
                plan.location().sessionId(), frame
        );
    }

    private List<ExecutorInstanceLocation> onlineLocations(String executorName) {
        return instanceDirectory.listOnline(executorName, clock.instant());
    }

    private Optional<ExecutorInstanceLocation> selectSharedLocation(
            String executorName,
            com.firefly.domain.ExecutorRoutingStrategy strategy,
            String routingKey
    ) {
        return selectLocation(onlineLocations(executorName), strategy, routingKey);
    }

    private Optional<ExecutorInstanceLocation> selectLocation(
            List<ExecutorInstanceLocation> locations,
            com.firefly.domain.ExecutorRoutingStrategy strategy,
            String routingKey
    ) {
        if (locations.isEmpty()) return Optional.empty();
        if (strategy == com.firefly.domain.ExecutorRoutingStrategy.CONSISTENT_HASH) {
            return locations.stream().max((left, right) -> Long.compareUnsigned(
                    rendezvousScore(routingKey, left.instanceId()),
                    rendezvousScore(routingKey, right.instanceId())
            ));
        }
        int index = strategy == com.firefly.domain.ExecutorRoutingStrategy.RANDOM
                ? ThreadLocalRandom.current().nextInt(locations.size())
                : Math.floorMod(routingCursors.computeIfAbsent(routingKey,
                        ignored -> new AtomicInteger()).getAndIncrement(), locations.size());
        return Optional.of(locations.get(index));
    }

    private Optional<ExecutorInstanceLocation> findLocation(
            List<ExecutorInstanceLocation> locations, String instanceId
    ) {
        return locations.stream().filter(location -> location.instanceId().equals(instanceId)).findFirst();
    }

    private long rendezvousScore(String routingKey, String instanceId) {
        long hash = 0xcbf29ce484222325L;
        String value = routingKey + '\u0000' + instanceId;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private RemoteDispatchResult dispatchPlans(
            RemoteDispatchRequest request,
            int expectedTargets,
            List<TargetPlan> plans
    ) {
        saveDispatch(request, expectedTargets, plans);
        plans.forEach(plan -> send(
                plan.target(), request, plan.targetExecutionId(), plan.shardIndex(), plan.shardTotal()
        ));
        return new RemoteDispatchResult(
                expectedTargets,
                plans.size(),
                plans.stream().map(plan -> plan.target().instanceId()).toList()
        );
    }

    private RemoteDispatchResult dispatchRetryPlans(
            RemoteDispatchRequest request,
            int parentExpectedTargets,
            int requestedTargets,
            List<TargetPlan> plans,
            List<ExecutionTargetRecord> sourceTargets
    ) {
        List<ExecutionTargetRecord> carried = sourceTargets.stream()
                .filter(target -> request.retryScope() == ExecutorRetryScope.FAILED_TARGETS_ONLY)
                .filter(target -> target.status() == ExecutionStatus.SUCCEEDED)
                .toList();
        saveDispatch(request, parentExpectedTargets, plans, carried);
        plans.forEach(plan -> send(
                plan.target(), request, plan.targetExecutionId(), plan.shardIndex(), plan.shardTotal()
        ));
        return new RemoteDispatchResult(
                requestedTargets,
                plans.size(),
                plans.stream().map(plan -> plan.target().instanceId()).toList()
        );
    }

    private void saveDispatch(
            RemoteDispatchRequest request,
            int expectedTargets,
            List<TargetPlan> plans
    ) {
        saveDispatch(request, expectedTargets, plans, List.of());
    }

    private void saveDispatch(
            RemoteDispatchRequest request,
            int expectedTargets,
            List<TargetPlan> plans,
            List<ExecutionTargetRecord> carriedTargets
    ) {
        Instant now = clock.instant();
        executionRepository.saveExecution(new ExecutionRecord(
                request.context().executionId(),
                request.rootExecutionId(),
                request.runAttempt(),
                request.context().jobId(),
                request.context().scheduledFireTime(),
                request.context().dispatchTime(),
                request.dispatchMode(),
                request.completionPolicy(),
                plans.isEmpty() && carriedTargets.isEmpty() ? ExecutionStatus.FAILED : ExecutionStatus.DISPATCHED,
                expectedTargets,
                plans.size() + carriedTargets.size(),
                request.ownerNodeId(),
                request.fencingToken(),
                now,
                now
        ));
        List<ExecutionTargetRecord> plannedTargets = plans.stream().map(plan -> new ExecutionTargetRecord(
                plan.targetExecutionId(),
                request.context().executionId(),
                plan.target().instanceId(),
                gatewayNodeId,
                plan.shardIndex(),
                ExecutionStatus.DISPATCHED,
                1,
                null,
                null,
                "",
                now,
                now
        )).toList();
        List<ExecutionTargetRecord> carried = carriedTargets.stream().map(source ->
                new ExecutionTargetRecord(
                        carriedTargetExecutionId(request, source),
                        request.context().executionId(),
                        source.instanceId(),
                        source.gatewayNodeId(),
                        source.shardIndex(),
                        ExecutionStatus.SUCCEEDED,
                        source.attempt(),
                        now,
                        now,
                        "",
                        now,
                        now
                )
        ).toList();
        executionRepository.saveTargets(Stream.concat(plannedTargets.stream(), carried.stream()).toList());
    }

    private String carriedTargetExecutionId(RemoteDispatchRequest request, ExecutionTargetRecord source) {
        String suffix = source.shardIndex() == null
                ? "instance:" + source.instanceId()
                : "shard:" + source.shardIndex();
        return request.context().executionId() + "@carry:" + suffix;
    }

    private void send(
            NettyExecutorConnectionRegistry.ConnectionTarget target,
            RemoteDispatchRequest request,
            String executionId,
            Integer shardIndex,
            Integer shardTotal
    ) {
        target.channel().writeAndFlush(codec.encode(triggerMessage(
                request, executionId, target.instanceId(), shardIndex, shardTotal
        )) + "\n");
    }

    private NettyExecutorMessage triggerMessage(
            RemoteDispatchRequest request,
            String executionId,
            String targetInstanceId,
            Integer shardIndex,
            Integer shardTotal
    ) {
        ExecutionContext context = request.context();
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("executionId", executionId);
        payload.put("parentExecutionId", context.executionId());
        payload.put("rootExecutionId", request.rootExecutionId());
        payload.put("runAttempt", Integer.toString(request.runAttempt()));
        payload.put("jobId", context.jobId());
        payload.put("handlerName", request.handlerName());
        payload.put("targetInstanceId", targetInstanceId);
        payload.put("dispatchMode", request.dispatchMode().name());
        payload.put("completionPolicy", request.completionPolicy().name());
        payload.put("ownerNodeId", request.ownerNodeId());
        payload.put("fencingToken", Long.toString(request.fencingToken()));
        payload.put("scheduledFireTime", context.scheduledFireTime().toString());
        payload.put("dispatchTime", context.dispatchTime().toString());
        context.parameters().forEach((key, value) -> payload.put("param." + key, value));
        payload.putAll(request.traceCarrier().values());
        FireflyTelemetry.inject(Context.current(), payload);
        if (shardIndex != null && shardTotal != null) {
            payload.put("param.firefly.shard.index", shardIndex.toString());
            payload.put("param.firefly.shard.total", shardTotal.toString());
        }
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(), NettyExecutorMessageType.TRIGGER_JOB, payload
        );
    }

    private record TargetPlan(
            NettyExecutorConnectionRegistry.ConnectionTarget target,
            String targetExecutionId,
            Integer shardIndex,
            Integer shardTotal
    ) {
    }

    private record DirectoryTargetPlan(
            ExecutorInstanceLocation location,
            String targetExecutionId,
            Integer shardIndex,
            Integer shardTotal
    ) {
    }
}
