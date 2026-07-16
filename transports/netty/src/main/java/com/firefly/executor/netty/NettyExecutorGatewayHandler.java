package com.firefly.executor.netty;

import com.firefly.catalog.SchedulerCatalog;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.executor.ExecutorRegistry;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.ExecutionStatus;
import com.firefly.metrics.SchedulerMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

final class NettyExecutorGatewayHandler extends SimpleChannelInboundHandler<String> {
    private static final Logger log = Logger.getLogger(NettyExecutorGatewayHandler.class.getName());

    private final ExecutorRegistry executorRegistry;
    private final NettyExecutorConnectionRegistry connectionRegistry;
    private final NettyExecutorJsonCodec codec;
    private final Clock clock;
    private final SchedulerCatalog schedulerCatalog;
    private final boolean autoCreateExecutorDefinitions;
    private final String gatewayNodeId;
    private final ExecutionRepository executionRepository;
    private final java.util.function.BiConsumer<String, Instant> dispatchAcknowledger;
    private final java.util.concurrent.Executor resultPersistenceExecutor;
    private final String executorAuthToken;
    private final java.util.function.BiConsumer<String, Boolean> retryScheduler;
    private final SchedulerMetrics metrics;

    NettyExecutorGatewayHandler(
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            NettyExecutorJsonCodec codec,
            Clock clock,
            SchedulerCatalog schedulerCatalog,
            boolean autoCreateExecutorDefinitions,
            String gatewayNodeId,
            ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger,
            java.util.concurrent.Executor resultPersistenceExecutor,
            String executorAuthToken,
            java.util.function.BiConsumer<String, Boolean> retryScheduler
    ) {
        this(executorRegistry, connectionRegistry, codec, clock, schedulerCatalog,
                autoCreateExecutorDefinitions, gatewayNodeId, executionRepository,
                dispatchAcknowledger, resultPersistenceExecutor, executorAuthToken, retryScheduler,
                new SchedulerMetrics());
    }

    NettyExecutorGatewayHandler(
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            NettyExecutorJsonCodec codec,
            Clock clock,
            SchedulerCatalog schedulerCatalog,
            boolean autoCreateExecutorDefinitions,
            String gatewayNodeId,
            ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger,
            java.util.concurrent.Executor resultPersistenceExecutor,
            String executorAuthToken,
            java.util.function.BiConsumer<String, Boolean> retryScheduler,
            SchedulerMetrics metrics
    ) {
        this.executorRegistry = executorRegistry;
        this.connectionRegistry = connectionRegistry;
        this.codec = codec;
        this.clock = clock;
        this.schedulerCatalog = schedulerCatalog;
        this.autoCreateExecutorDefinitions = autoCreateExecutorDefinitions;
        this.gatewayNodeId = gatewayNodeId;
        this.executionRepository = executionRepository;
        this.dispatchAcknowledger = dispatchAcknowledger;
        this.resultPersistenceExecutor = resultPersistenceExecutor;
        this.executorAuthToken = executorAuthToken;
        this.retryScheduler = retryScheduler;
        this.metrics = metrics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String frame) {
        NettyExecutorMessage message = codec.decode(frame.trim());
        switch (message.type()) {
            case REGISTER_EXECUTOR -> register(context, message.payload());
            case HEARTBEAT -> heartbeat(message.payload());
            case UNREGISTER_EXECUTOR -> unregister(context, message.payload());
            case ACK_JOB -> acknowledge(context, message.payload());
            case REPORT_RESULT -> reportResult(context, message.payload());
            case TRIGGER_JOB -> throw new IllegalStateException("gateway must not receive TRIGGER_JOB");
            case REGISTERED, REGISTER_REJECTED ->
                    throw new IllegalStateException("gateway must not receive registration responses");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        connectionRegistry.unregister(context.channel()).ifPresent(key ->
                executorRegistry.markOffline(key.executorName(), key.instanceId()));
    }

    private void register(ChannelHandlerContext context, Map<String, String> payload) {
        String executorName = payload.get("executorName");
        String instanceId = payload.get("instanceId");
        String sessionId = payload.getOrDefault("sessionId", instanceId);
        String serviceName = payload.getOrDefault("serviceName", instanceId);
        int protocolVersion;
        try {
            protocolVersion = Integer.parseInt(payload.getOrDefault("protocolVersion", "1"));
        } catch (NumberFormatException invalidVersion) {
            rejectRegistration(context, "invalid protocol version");
            return;
        }
        if (!NettyExecutorProtocol.supports(protocolVersion)) {
            rejectRegistration(context, "unsupported protocol version: " + protocolVersion);
            return;
        }
        java.util.Set<String> clientCapabilities = parseCapabilities(payload.get("capabilities"));
        if (payload.containsKey("capabilities")
                && !NettyExecutorProtocol.hasRequiredCapabilities(clientCapabilities)) {
            rejectRegistration(context, "required capabilities are missing");
            return;
        }
        java.util.Set<String> negotiatedCapabilities = clientCapabilities.isEmpty()
                ? NettyExecutorProtocol.SERVER_CAPABILITIES
                : NettyExecutorProtocol.SERVER_CAPABILITIES.stream()
                .filter(clientCapabilities::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!authorized(payload.getOrDefault("authToken", ""))) {
            context.close();
            throw new IllegalStateException("executor authentication failed");
        }
        ExecutorDefinition definition = schedulerCatalog.findExecutor(executorName)
                .orElseGet(() -> autoCreateDefinition(executorName));
        if (!definition.enabled()) {
            throw new IllegalStateException("executor definition is disabled: " + executorName);
        }
        if (!definition.protocols().contains(ExecutorProtocol.TCP)) {
            throw new IllegalStateException("executor definition does not allow TCP registration: " + executorName);
        }
        Instant now = clock.instant();
        executorRegistry.register(ExecutorInstance.builder()
                .executorName(executorName)
                .instanceId(instanceId)
                .sessionId(sessionId)
                .gatewayNodeId(gatewayNodeId)
                .serviceName(serviceName)
                .host(remoteHost(context))
                .port(0)
                .protocol(ExecutorProtocol.TCP)
                .registeredAt(now)
                .lastHeartbeatAt(now)
                .build());
        connectionRegistry.register(executorName, instanceId, sessionId, context.channel());
        write(context, new NettyExecutorMessage(
                java.util.UUID.randomUUID().toString(),
                NettyExecutorMessageType.REGISTERED,
                Map.of(
                        "protocolVersion", Integer.toString(NettyExecutorProtocol.CURRENT_VERSION),
                        "capabilities", NettyExecutorProtocol.encodeCapabilities(
                                negotiatedCapabilities
                        ),
                        "gatewayNodeId", gatewayNodeId
                )
        ));
        log.info(() -> "executor registered: executor=" + executorName
                + ", instance=" + instanceId
                + ", service=" + serviceName
                + ", remote=" + remoteHost(context));
    }

    private void rejectRegistration(ChannelHandlerContext context, String reason) {
        NettyExecutorMessage rejection = new NettyExecutorMessage(
                java.util.UUID.randomUUID().toString(),
                NettyExecutorMessageType.REGISTER_REJECTED,
                Map.of(
                        "reason", reason,
                        "minimumVersion", Integer.toString(NettyExecutorProtocol.MIN_SUPPORTED_VERSION),
                        "maximumVersion", Integer.toString(NettyExecutorProtocol.CURRENT_VERSION)
                )
        );
        context.writeAndFlush(codec.encode(rejection) + "\n")
                .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    private java.util.Set<String> parseCapabilities(String value) {
        return NettyExecutorProtocol.decodeCapabilities(value);
    }

    private void write(ChannelHandlerContext context, NettyExecutorMessage message) {
        context.writeAndFlush(codec.encode(message) + "\n");
    }

    private boolean authorized(String provided) {
        if (executorAuthToken.isBlank()) return true;
        return java.security.MessageDigest.isEqual(
                executorAuthToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private ExecutorDefinition autoCreateDefinition(String executorName) {
        if (!autoCreateExecutorDefinitions) {
            throw new IllegalStateException("unknown executor definition: " + executorName);
        }
        ExecutorDefinition definition = ExecutorDefinition.builder()
                .name(executorName)
                .description("Auto-created from Netty executor registration")
                .protocols(java.util.Set.of(ExecutorProtocol.TCP))
                .build();
        schedulerCatalog.saveExecutor(definition);
        return definition;
    }

    private void heartbeat(Map<String, String> payload) {
        String executorName = payload.get("executorName");
        String instanceId = payload.get("instanceId");
        String sessionId = payload.getOrDefault("sessionId", instanceId);
        executorRegistry.find(executorName, instanceId)
                .filter(instance -> instance.sessionId().equals(sessionId))
                .ifPresent(instance -> executorRegistry.heartbeat(executorName, instanceId, clock.instant()));
    }

    private void unregister(ChannelHandlerContext context, Map<String, String> payload) {
        String executorName = payload.get("executorName");
        String instanceId = payload.get("instanceId");
        String sessionId = payload.getOrDefault("sessionId", instanceId);
        executorRegistry.find(executorName, instanceId)
                .filter(instance -> instance.sessionId().equals(sessionId))
                .ifPresent(instance -> executorRegistry.markOffline(executorName, instanceId));
        connectionRegistry.unregister(context.channel());
        log.info(() -> "executor unregistered: executor=" + executorName
                + ", instance=" + instanceId);
    }

    private void acknowledge(ChannelHandlerContext context, Map<String, String> payload) {
        if (!validReporter(context, payload)) return;
        Instant now = clock.instant();
        String parentExecutionId = payload.getOrDefault("parentExecutionId", payload.get("executionId"));
        persist(context, () -> {
            if (!validFencing(parentExecutionId, payload)) return;
            Instant dispatchedAt = target(parentExecutionId, payload.get("executionId"))
                    .map(com.firefly.execution.ExecutionTargetRecord::updatedAt)
                    .orElse(now);
            com.firefly.execution.ExecutionMutationResult result = executionRepository.acknowledgeResult(
                    payload.get("executionId"), now
            );
            if (result.accepted() && deliveryComplete(parentExecutionId)) {
                dispatchAcknowledger.accept(parentExecutionId, now);
            }
            if (result == com.firefly.execution.ExecutionMutationResult.APPLIED) {
                metrics.observeAcknowledgementDelay(java.time.Duration.between(dispatchedAt, now));
            }
        });
    }

    private boolean deliveryComplete(String parentExecutionId) {
        return executionRepository.findExecution(parentExecutionId).map(execution -> {
            java.util.List<com.firefly.execution.ExecutionTargetRecord> targets =
                    executionRepository.listTargets(parentExecutionId);
            return targets.size() == execution.expectedTargets()
                    && targets.stream().allMatch(target -> target.acknowledgedAt() != null);
        }).orElse(false);
    }

    private void reportResult(ChannelHandlerContext context, Map<String, String> payload) {
        if (!validReporter(context, payload)) return;
        ExecutionStatus status = "SUCCEEDED".equalsIgnoreCase(payload.get("status"))
                ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        String parentExecutionId = payload.getOrDefault("parentExecutionId", payload.get("executionId"));
        Instant now = clock.instant();
        persist(context, () -> {
            if (!validFencing(parentExecutionId, payload)) return;
            Instant startedAt = target(parentExecutionId, payload.get("executionId"))
                    .map(target -> target.acknowledgedAt() == null ? target.updatedAt() : target.acknowledgedAt())
                    .orElse(now);
            com.firefly.execution.ExecutionMutationResult result = executionRepository.completeResult(
                    payload.get("executionId"), status, payload.getOrDefault("errorMessage", ""), now
            );
            if (result.accepted() && deliveryComplete(parentExecutionId)) {
                dispatchAcknowledger.accept(parentExecutionId, now);
            }
            if (result == com.firefly.execution.ExecutionMutationResult.APPLIED) {
                metrics.observeExecutionDuration(java.time.Duration.between(startedAt, now));
                executionRepository.findExecution(parentExecutionId)
                        .filter(execution -> execution.status() == ExecutionStatus.FAILED
                                || execution.status() == ExecutionStatus.PARTIAL)
                        .ifPresent(execution -> retryScheduler.accept(parentExecutionId, false));
            }
        });
    }

    private java.util.Optional<com.firefly.execution.ExecutionTargetRecord> target(
            String parentExecutionId, String targetExecutionId
    ) {
        return executionRepository.listTargets(parentExecutionId).stream()
                .filter(target -> target.targetExecutionId().equals(targetExecutionId))
                .findFirst();
    }

    private void persist(ChannelHandlerContext context, Runnable task) {
        try {
            resultPersistenceExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    resumeReadsBelowLowWatermark(context);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            if (context.channel().config().isAutoRead()) {
                context.channel().config().setAutoRead(false);
                log.warning("pausing executor channel reads because result persistence is saturated");
            }
            context.executor().schedule(() -> persist(context, task), 10, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private void resumeReadsBelowLowWatermark(ChannelHandlerContext context) {
        if (!(resultPersistenceExecutor instanceof java.util.concurrent.ThreadPoolExecutor pool)) return;
        int capacity = pool.getQueue().size() + pool.getQueue().remainingCapacity();
        if (pool.getQueue().size() > capacity / 2 || !context.channel().isActive()) return;
        context.executor().execute(() -> {
            if (!context.channel().config().isAutoRead()) {
                context.channel().config().setAutoRead(true);
                context.read();
            }
        });
    }

    private boolean validReporter(ChannelHandlerContext context, Map<String, String> payload) {
        String instanceId = payload.get("instanceId");
        String sessionId = payload.get("sessionId");
        return instanceId != null && sessionId != null
                && connectionRegistry.isCurrent(context.channel(), instanceId, sessionId);
    }

    private boolean validFencing(String parentExecutionId, Map<String, String> payload) {
        String owner = payload.get("ownerNodeId");
        String token = payload.get("fencingToken");
        if (owner == null || token == null) return false;
        long fencingToken;
        try {
            fencingToken = Long.parseLong(token);
        } catch (NumberFormatException ignored) {
            return false;
        }
        boolean validExecution = executionRepository.findExecution(parentExecutionId)
                .filter(execution -> execution.ownerNodeId().equals(owner))
                .filter(execution -> execution.fencingToken() == fencingToken)
                .filter(execution -> execution.status() == ExecutionStatus.DISPATCHING
                        || execution.status() == ExecutionStatus.DISPATCHED
                        || execution.status() == ExecutionStatus.RUNNING)
                .isPresent();
        if (!validExecution) return false;
        return executionRepository.listTargets(parentExecutionId).stream()
                .filter(target -> target.targetExecutionId().equals(payload.get("executionId")))
                .anyMatch(target -> target.instanceId().equals(payload.get("instanceId")));
    }

    private String remoteHost(ChannelHandlerContext context) {
        if (context.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getHostString();
        }
        return "unknown";
    }
}
