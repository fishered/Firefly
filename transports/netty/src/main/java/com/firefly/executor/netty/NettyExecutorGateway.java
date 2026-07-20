package com.firefly.executor.netty;

import com.firefly.engine.ExecutionCommand;
import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.catalog.SchedulerCatalog;
import com.firefly.domain.ExecutionContext;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRoutingStrategy;
import com.firefly.domain.ExecutorRetryScope;
import com.firefly.executor.ExecutorRegistry;
import com.firefly.executor.InMemoryExecutorRegistry;
import com.firefly.executor.ExecutorInstanceDirectory;
import com.firefly.executor.ExecutorInstanceLocation;
import com.firefly.executor.InMemoryExecutorInstanceDirectory;
import com.firefly.executor.RemoteDispatchRequest;
import com.firefly.executor.RemoteDispatchResult;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.metrics.SchedulerMetrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Netty server used by the scheduler side to accept executor long connections.
 */
public final class NettyExecutorGateway implements AutoCloseable {
    private final int port;
    private final ExecutorRegistry executorRegistry;
    private final NettyExecutorConnectionRegistry connectionRegistry;
    private final Clock clock;
    private final SchedulerCatalog schedulerCatalog;
    private final boolean autoCreateExecutorDefinitions;
    private final String gatewayNodeId;
    private final ExecutionRepository executionRepository;
    private final java.util.function.BiConsumer<String, Instant> dispatchAcknowledger;
    private final String executorAuthToken;
    private final java.util.function.BiConsumer<String, Boolean> retryScheduler;
    private final SchedulerMetrics metrics;
    private final NettyExecutorGatewayOptions options;
    private final java.util.concurrent.ThreadPoolExecutor resultPersistenceExecutor;
    private final ReloadingNettyTlsContext tlsContext;
    private final ExecutorInstanceDirectory instanceDirectory;
    private final NettyGatewayForwardingTransport forwardingTransport;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            routingCursors = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile java.util.function.BooleanSupplier registrationAdmission = () -> true;
    private final NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyExecutorGateway(int port) {
        this(
                port,
                new InMemoryExecutorRegistry(),
                new NettyExecutorConnectionRegistry(),
                Clock.systemUTC(),
                new InMemorySchedulerCatalog(),
                true,
                "local",
                new InMemoryExecutionRepository()
        );
    }

    public NettyExecutorGateway(
            int port,
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            Clock clock
    ) {
        this(port, executorRegistry, connectionRegistry, clock, new InMemorySchedulerCatalog(), true, "local",
                new InMemoryExecutionRepository());
    }

    public NettyExecutorGateway(
            int port,
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            Clock clock,
            SchedulerCatalog schedulerCatalog,
            boolean autoCreateExecutorDefinitions
    ) {
        this(
                port,
                executorRegistry,
                connectionRegistry,
                clock,
                schedulerCatalog,
                autoCreateExecutorDefinitions,
                "local",
                new InMemoryExecutionRepository()
        );
    }

    public NettyExecutorGateway(
            int port,
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            Clock clock,
            SchedulerCatalog schedulerCatalog,
            boolean autoCreateExecutorDefinitions,
            String gatewayNodeId
    ) {
        this(port, executorRegistry, connectionRegistry, clock, schedulerCatalog, autoCreateExecutorDefinitions,
                gatewayNodeId, new InMemoryExecutionRepository());
    }

    public NettyExecutorGateway(
            int port,
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            Clock clock,
            SchedulerCatalog schedulerCatalog,
            boolean autoCreateExecutorDefinitions,
            String gatewayNodeId,
            ExecutionRepository executionRepository
    ) {
        this(port, executorRegistry, connectionRegistry, clock, schedulerCatalog, autoCreateExecutorDefinitions,
                gatewayNodeId, executionRepository, (executionId, acknowledgedAt) -> { });
    }

    public NettyExecutorGateway(
            int port,
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            Clock clock,
            SchedulerCatalog schedulerCatalog,
            boolean autoCreateExecutorDefinitions,
            String gatewayNodeId,
            ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger
    ) {
        this(port, executorRegistry, connectionRegistry, clock, schedulerCatalog, autoCreateExecutorDefinitions,
                gatewayNodeId, executionRepository, dispatchAcknowledger, "");
    }

    public NettyExecutorGateway(
            int port,
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            Clock clock,
            SchedulerCatalog schedulerCatalog,
            boolean autoCreateExecutorDefinitions,
            String gatewayNodeId,
            ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger,
            String executorAuthToken
    ) {
        this(port, executorRegistry, connectionRegistry, clock, schedulerCatalog,
                autoCreateExecutorDefinitions, gatewayNodeId, executionRepository,
                dispatchAcknowledger, executorAuthToken, (executionId, timeout) -> { });
    }

    public NettyExecutorGateway(
            int port, ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry, Clock clock,
            SchedulerCatalog schedulerCatalog, boolean autoCreateExecutorDefinitions,
            String gatewayNodeId, ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger,
            String executorAuthToken,
            java.util.function.BiConsumer<String, Boolean> retryScheduler
    ) {
        this(port, executorRegistry, connectionRegistry, clock, schedulerCatalog,
                autoCreateExecutorDefinitions, gatewayNodeId, executionRepository,
                dispatchAcknowledger, executorAuthToken, retryScheduler, new SchedulerMetrics());
    }

    public NettyExecutorGateway(
            int port, ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry, Clock clock,
            SchedulerCatalog schedulerCatalog, boolean autoCreateExecutorDefinitions,
            String gatewayNodeId, ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger,
            String executorAuthToken,
            java.util.function.BiConsumer<String, Boolean> retryScheduler,
            SchedulerMetrics metrics
    ) {
        this(port, executorRegistry, connectionRegistry, clock, schedulerCatalog,
                autoCreateExecutorDefinitions, gatewayNodeId, executionRepository,
                dispatchAcknowledger, executorAuthToken, retryScheduler, metrics,
                NettyExecutorGatewayOptions.defaults());
    }

    public NettyExecutorGateway(
            int port, ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry, Clock clock,
            SchedulerCatalog schedulerCatalog, boolean autoCreateExecutorDefinitions,
            String gatewayNodeId, ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger,
            String executorAuthToken,
            java.util.function.BiConsumer<String, Boolean> retryScheduler,
            SchedulerMetrics metrics,
            NettyExecutorGatewayOptions options
    ) {
        this(port, executorRegistry, connectionRegistry, clock, schedulerCatalog,
                autoCreateExecutorDefinitions, gatewayNodeId, executionRepository,
                dispatchAcknowledger, executorAuthToken, retryScheduler, metrics, options,
                new InMemoryExecutorInstanceDirectory());
    }

    public NettyExecutorGateway(
            int port, ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry, Clock clock,
            SchedulerCatalog schedulerCatalog, boolean autoCreateExecutorDefinitions,
            String gatewayNodeId, ExecutionRepository executionRepository,
            java.util.function.BiConsumer<String, Instant> dispatchAcknowledger,
            String executorAuthToken,
            java.util.function.BiConsumer<String, Boolean> retryScheduler,
            SchedulerMetrics metrics,
            NettyExecutorGatewayOptions options,
            ExecutorInstanceDirectory instanceDirectory
    ) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.port = port;
        this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
        this.connectionRegistry = Objects.requireNonNull(connectionRegistry, "connectionRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.schedulerCatalog = Objects.requireNonNull(schedulerCatalog, "schedulerCatalog");
        this.autoCreateExecutorDefinitions = autoCreateExecutorDefinitions;
        this.gatewayNodeId = Objects.requireNonNull(gatewayNodeId, "gatewayNodeId");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.dispatchAcknowledger = Objects.requireNonNull(dispatchAcknowledger, "dispatchAcknowledger");
        this.executorAuthToken = executorAuthToken == null ? "" : executorAuthToken;
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.options = Objects.requireNonNull(options, "options");
        this.tlsContext = new ReloadingNettyTlsContext(options.tls(), options.tlsReloadInterval());
        this.instanceDirectory = Objects.requireNonNull(instanceDirectory, "instanceDirectory");
        this.forwardingTransport = new NettyGatewayForwardingTransport(connectionRegistry, options, metrics);
        this.resultPersistenceExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(options.resultQueueCapacity()),
                r -> {
                    Thread thread = new Thread(r, "firefly-execution-results");
                    thread.setDaemon(false);
                    return thread;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void start() throws InterruptedException {
        forwardingTransport.start();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        io.netty.handler.ssl.SslContext currentTls = tlsContext.current();
                        if (currentTls != null) {
                            channel.pipeline().addLast("tls", currentTls.newHandler(channel.alloc()));
                        }
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(options.maxFrameLength()))
                                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                                .addLast(new NettyExecutorGatewayHandler(
                                        executorRegistry,
                                        connectionRegistry,
                                        codec,
                                        clock,
                                        schedulerCatalog,
                                        autoCreateExecutorDefinitions,
                                        gatewayNodeId,
                                        executionRepository,
                                        dispatchAcknowledger,
                                        resultPersistenceExecutor,
                                        executorAuthToken,
                                        retryScheduler,
                                        metrics,
                                        instanceDirectory,
                                        options.advertisedInternalAddress(),
                                        options.instanceLocationRefreshInterval(),
                                        options.instanceLocationLease(),
                                        () -> registrationAdmission.getAsBoolean()
                                ));
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
    }

    public boolean dispatch(String executorName, ExecutionCommand command) {
        return connectionRegistry.select(executorName)
                .map(channel -> {
                    channel.writeAndFlush(codec.encode(triggerMessage(command)) + "\n");
                    return true;
                })
                .orElse(false);
    }

    public boolean dispatch(String executorName, String handlerName, ExecutionContext context) {
        return dispatch(new RemoteDispatchRequest(
                executorName,
                handlerName,
                context,
                ExecutorDispatchMode.UNICAST,
                ExecutorRoutingStrategy.ROUND_ROBIN,
                ExecutorCompletionPolicy.ALL_SUCCESS,
                1,
                context.executionId()
        )).accepted();
    }

    public RemoteDispatchResult dispatch(RemoteDispatchRequest request) {
        if (schedulerCatalog.findExecutor(request.executorName())
                .map(definition -> !definition.enabled()).orElse(false)) {
            return RemoteDispatchResult.unavailable();
        }
        return switch (request.dispatchMode()) {
            case UNICAST -> dispatchUnicast(request);
            case BROADCAST -> dispatchBroadcast(request);
            case SHARDING -> dispatchShards(request);
        };
    }

    public ExecutorRegistry executorRegistry() {
        return executorRegistry;
    }

    public NettyExecutorConnectionRegistry connectionRegistry() {
        return connectionRegistry;
    }

    public boolean hasRoute(String executorName) {
        return !connectionRegistry.list(executorName).isEmpty()
                || !instanceDirectory.listOnline(executorName, clock.instant()).isEmpty();
    }

    public void setRegistrationAdmission(java.util.function.BooleanSupplier registrationAdmission) {
        this.registrationAdmission = Objects.requireNonNull(registrationAdmission, "registrationAdmission");
    }

    public int connectedExecutorCount() {
        return connectionRegistry.list().size();
    }

    public int disconnectAllExecutors() {
        return connectionRegistry.closeAll();
    }

    public int cancel(String executionId, String reason) {
        ExecutionRecord execution = executionRepository.findExecution(executionId).orElse(null);
        if (execution == null) return 0;
        int sent = 0;
        for (ExecutionTargetRecord target : executionRepository.listTargets(executionId)) {
            var connection = connectionRegistry.findInstance(target.instanceId());
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("executionId", target.targetExecutionId());
            payload.put("parentExecutionId", executionId);
            payload.put("instanceId", target.instanceId());
            payload.put("ownerNodeId", execution.ownerNodeId());
            payload.put("fencingToken", Long.toString(execution.fencingToken()));
            payload.put("reason", reason == null || reason.isBlank() ? "cancelled by operator" : reason);
            String frame = codec.encode(new NettyExecutorMessage(
                    UUID.randomUUID().toString(), NettyExecutorMessageType.CANCEL_JOB, payload
            ));
            if (connection.isPresent()) {
                if (!connection.get().supports("CANCELLATION")) continue;
                connection.get().channel().writeAndFlush(frame + "\n");
                sent++;
                continue;
            }
            var location = instanceDirectory.findOnlineInstance(target.instanceId(), clock.instant());
            if (location.isPresent() && forwardingTransport.forward(
                    location.get().gatewayAddress(), location.get().executorName(), target.instanceId(),
                    location.get().sessionId(), frame
            )) sent++;
        }
        return sent;
    }

    public int isolate(String executorName) {
        return isolateDetailed(executorName).disconnectedInstances();
    }

    public com.firefly.executor.ExecutorIsolationResult isolateDetailed(String executorName) {
        int closed = connectionRegistry.closeExecutor(executorName);
        java.util.List<String> addresses = instanceDirectory.listOnline(executorName, clock.instant()).stream()
                .filter(location -> !location.gatewayNodeId().equals(gatewayNodeId))
                .map(ExecutorInstanceLocation::gatewayAddress)
                .filter(address -> !address.isBlank())
                .distinct()
                .toList();
        java.util.List<String> failed = addresses.stream()
                .filter(address -> !forwardingTransport.isolate(address, executorName))
                .toList();
        executorRegistry.listAll().stream()
                .filter(instance -> instance.executorName().equals(executorName))
                .forEach(instance -> executorRegistry.markOffline(executorName, instance.instanceId()));
        return new com.firefly.executor.ExecutorIsolationResult(
                closed, addresses.size(), failed.size(), failed
        );
    }

    private RemoteDispatchResult dispatchUnicast(RemoteDispatchRequest request) {
        var sharedTarget = selectSharedLocation(
                request.executorName(), request.routingStrategy(), request.routingKey()
        );
        if (sharedTarget.isPresent()) {
            return dispatchDirectoryPlans(request, 1, java.util.List.of(new DirectoryTargetPlan(
                    sharedTarget.get(), request.context().executionId(), null, null
            )), java.util.List.of());
        }
        var target = connectionRegistry.select(
                request.executorName(),
                request.routingStrategy(),
                request.routingKey()
        );
        if (target.isEmpty()) {
            saveDispatch(request, 1, java.util.List.of());
            return new RemoteDispatchResult(1, 0, java.util.List.of());
        }
        return dispatchPlans(request, 1, java.util.List.of(new TargetPlan(
                target.get(), request.context().executionId(), null, null
        )));
    }

    private RemoteDispatchResult dispatchBroadcast(RemoteDispatchRequest request) {
        java.util.List<ExecutorInstanceLocation> sharedLocations = onlineLocations(request.executorName());
        if (!sharedLocations.isEmpty()) return dispatchBroadcastShared(request, sharedLocations);
        java.util.List<ExecutionTargetRecord> existingTargets =
                executionRepository.listTargets(request.context().executionId());
        if (!existingTargets.isEmpty()) {
            java.util.List<TargetPlan> retryPlans = existingTargets.stream()
                    .map(existing -> connectionRegistry.find(request.executorName(), existing.instanceId())
                            .map(target -> new TargetPlan(
                                    target, existing.targetExecutionId(), existing.shardIndex(), null
                            )))
                    .flatMap(java.util.Optional::stream)
                    .toList();
            return dispatchPlans(request, existingTargets.size(), retryPlans);
        }
        java.util.Optional<java.util.List<ExecutionTargetRecord>> retryTargets = retrySourceTargets(request);
        if (retryTargets.isPresent()) {
            java.util.List<ExecutionTargetRecord> sourceTargets = retryTargets.get();
            java.util.List<ExecutionTargetRecord> requestedTargets = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                    ? sourceTargets
                    : sourceTargets.stream()
                            .filter(target -> target.status() != ExecutionStatus.SUCCEEDED)
                            .toList();
            java.util.List<TargetPlan> plans = requestedTargets.stream()
                    .map(existing -> connectionRegistry.find(request.executorName(), existing.instanceId())
                            .map(target -> new TargetPlan(
                                    target,
                                    request.context().executionId() + "@instance:" + existing.instanceId(),
                                    null,
                                    null
                            )))
                    .flatMap(java.util.Optional::stream)
                    .toList();
            int parentExpectedTargets = retrySourceExecution(request)
                    .map(ExecutionRecord::expectedTargets)
                    .orElse(sourceTargets.size());
            return dispatchRetryPlans(
                    request, parentExpectedTargets, requestedTargets.size(), plans, sourceTargets
            );
        }
        java.util.List<NettyExecutorConnectionRegistry.ConnectionTarget> targets =
                connectionRegistry.list(request.executorName());
        java.util.List<TargetPlan> plans = targets.stream()
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
        java.util.List<ExecutorInstanceLocation> sharedLocations = onlineLocations(request.executorName());
        if (!sharedLocations.isEmpty()) return dispatchShardsShared(request, sharedLocations);
        java.util.List<ExecutionTargetRecord> sourceTargets = retrySourceTargets(request).orElse(java.util.List.of());
        java.util.Set<Integer> successfulShards = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                ? java.util.Set.of()
                : sourceTargets
                .stream()
                .filter(target -> target.status() == ExecutionStatus.SUCCEEDED)
                .map(ExecutionTargetRecord::shardIndex)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.ArrayList<TargetPlan> plans = new java.util.ArrayList<>();
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

    private java.util.Optional<java.util.List<ExecutionTargetRecord>> retrySourceTargets(
            RemoteDispatchRequest request
    ) {
        return retrySourceExecution(request)
                .map(source -> executionRepository.listTargets(source.executionId()));
    }

    private java.util.Optional<ExecutionRecord> retrySourceExecution(RemoteDispatchRequest request) {
        if (request.runAttempt() <= 0) return java.util.Optional.empty();
        String sourceExecutionId = request.runAttempt() == 1
                ? request.rootExecutionId()
                : request.rootExecutionId() + "@attempt:" + (request.runAttempt() - 1);
        return executionRepository.findExecution(sourceExecutionId);
    }

    private RemoteDispatchResult dispatchBroadcastShared(
            RemoteDispatchRequest request, java.util.List<ExecutorInstanceLocation> locations
    ) {
        java.util.List<ExecutionTargetRecord> existingTargets =
                executionRepository.listTargets(request.context().executionId());
        if (!existingTargets.isEmpty()) {
            java.util.List<DirectoryTargetPlan> plans = existingTargets.stream()
                    .map(existing -> findLocation(locations, existing.instanceId())
                            .map(location -> new DirectoryTargetPlan(
                                    location, existing.targetExecutionId(), existing.shardIndex(), null
                            )))
                    .flatMap(java.util.Optional::stream)
                    .toList();
            return dispatchDirectoryPlans(request, existingTargets.size(), plans, java.util.List.of());
        }
        java.util.List<ExecutionTargetRecord> sourceTargets = retrySourceTargets(request).orElse(java.util.List.of());
        if (!sourceTargets.isEmpty()) {
            java.util.List<ExecutionTargetRecord> requested = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                    ? sourceTargets
                    : sourceTargets.stream().filter(target -> target.status() != ExecutionStatus.SUCCEEDED).toList();
            java.util.List<DirectoryTargetPlan> plans = requested.stream()
                    .map(existing -> findLocation(locations, existing.instanceId())
                            .map(location -> new DirectoryTargetPlan(
                                    location,
                                    request.context().executionId() + "@instance:" + existing.instanceId(),
                                    existing.shardIndex(), null
                            )))
                    .flatMap(java.util.Optional::stream)
                    .toList();
            java.util.List<ExecutionTargetRecord> carried = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                    ? java.util.List.of()
                    : sourceTargets.stream().filter(target -> target.status() == ExecutionStatus.SUCCEEDED).toList();
            int expected = retrySourceExecution(request).map(ExecutionRecord::expectedTargets)
                    .orElse(sourceTargets.size());
            return dispatchDirectoryPlans(request, expected, plans, carried, requested.size());
        }
        java.util.List<DirectoryTargetPlan> plans = locations.stream()
                .map(location -> new DirectoryTargetPlan(
                        location, request.context().executionId() + "@instance:" + location.instanceId(),
                        null, null
                ))
                .toList();
        return dispatchDirectoryPlans(request, locations.size(), plans, java.util.List.of());
    }

    private RemoteDispatchResult dispatchShardsShared(
            RemoteDispatchRequest request, java.util.List<ExecutorInstanceLocation> locations
    ) {
        java.util.List<ExecutionTargetRecord> sourceTargets = retrySourceTargets(request).orElse(java.util.List.of());
        java.util.Set<Integer> successfulShards = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                ? java.util.Set.of()
                : sourceTargets.stream()
                .filter(target -> target.status() == ExecutionStatus.SUCCEEDED)
                .map(ExecutionTargetRecord::shardIndex)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.ArrayList<DirectoryTargetPlan> plans = new java.util.ArrayList<>();
        for (int shard = 0; shard < request.shardCount(); shard++) {
            if (successfulShards.contains(shard)) continue;
            int shardIndex = shard;
            selectLocation(locations, request.routingStrategy(), request.routingKey() + ":" + shard)
                    .ifPresent(location -> plans.add(new DirectoryTargetPlan(
                            location, request.context().executionId() + "@shard:" + shardIndex,
                            shardIndex, request.shardCount()
                    )));
        }
        java.util.List<ExecutionTargetRecord> carried = request.retryScope() == ExecutorRetryScope.ALL_TARGETS
                ? java.util.List.of()
                : sourceTargets.stream().filter(target -> target.status() == ExecutionStatus.SUCCEEDED).toList();
        int requested = request.shardCount() - successfulShards.size();
        return dispatchDirectoryPlans(request, request.shardCount(), plans, carried, requested);
    }

    private RemoteDispatchResult dispatchDirectoryPlans(
            RemoteDispatchRequest request, int expectedTargets,
            java.util.List<DirectoryTargetPlan> plans,
            java.util.List<ExecutionTargetRecord> carriedTargets
    ) {
        return dispatchDirectoryPlans(request, expectedTargets, plans, carriedTargets, expectedTargets);
    }

    private RemoteDispatchResult dispatchDirectoryPlans(
            RemoteDispatchRequest request, int expectedTargets,
            java.util.List<DirectoryTargetPlan> plans,
            java.util.List<ExecutionTargetRecord> carriedTargets,
            int requestedTargets
    ) {
        saveDirectoryDispatch(request, expectedTargets, plans, carriedTargets);
        int accepted = 0;
        java.util.ArrayList<String> instances = new java.util.ArrayList<>();
        for (DirectoryTargetPlan plan : plans) {
            if (sendDirectory(plan, request)) {
                accepted++;
                instances.add(plan.location().instanceId());
            }
        }
        return new RemoteDispatchResult(requestedTargets, accepted, java.util.List.copyOf(instances));
    }

    private void saveDirectoryDispatch(
            RemoteDispatchRequest request, int expectedTargets,
            java.util.List<DirectoryTargetPlan> plans,
            java.util.List<ExecutionTargetRecord> carriedTargets
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
        java.util.List<ExecutionTargetRecord> planned = plans.stream().map(plan -> new ExecutionTargetRecord(
                plan.targetExecutionId(), request.context().executionId(), plan.location().instanceId(),
                plan.location().gatewayNodeId(), plan.shardIndex(), ExecutionStatus.DISPATCHED,
                1, null, null, "", now, now
        )).toList();
        java.util.List<ExecutionTargetRecord> carried = carriedTargets.stream().map(source ->
                new ExecutionTargetRecord(
                        carriedTargetExecutionId(request, source), request.context().executionId(),
                        source.instanceId(), source.gatewayNodeId(), source.shardIndex(), ExecutionStatus.SUCCEEDED,
                        source.attempt(), now, now, "", now, now
                )
        ).toList();
        executionRepository.saveTargets(java.util.stream.Stream.concat(planned.stream(), carried.stream()).toList());
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

    private java.util.List<ExecutorInstanceLocation> onlineLocations(String executorName) {
        return instanceDirectory.listOnline(executorName, clock.instant());
    }

    private java.util.Optional<ExecutorInstanceLocation> selectSharedLocation(
            String executorName, ExecutorRoutingStrategy strategy, String routingKey
    ) {
        return selectLocation(onlineLocations(executorName), strategy, routingKey);
    }

    private java.util.Optional<ExecutorInstanceLocation> selectLocation(
            java.util.List<ExecutorInstanceLocation> locations,
            ExecutorRoutingStrategy strategy,
            String routingKey
    ) {
        if (locations.isEmpty()) return java.util.Optional.empty();
        if (strategy == ExecutorRoutingStrategy.CONSISTENT_HASH) {
            return locations.stream().max((left, right) -> Long.compareUnsigned(
                    rendezvousScore(routingKey, left.instanceId()),
                    rendezvousScore(routingKey, right.instanceId())
            ));
        }
        int index = strategy == ExecutorRoutingStrategy.RANDOM
                ? java.util.concurrent.ThreadLocalRandom.current().nextInt(locations.size())
                : Math.floorMod(routingCursors.computeIfAbsent(routingKey,
                        ignored -> new java.util.concurrent.atomic.AtomicInteger()).getAndIncrement(), locations.size());
        return java.util.Optional.of(locations.get(index));
    }

    private java.util.Optional<ExecutorInstanceLocation> findLocation(
            java.util.List<ExecutorInstanceLocation> locations, String instanceId
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
            java.util.List<TargetPlan> plans
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
            java.util.List<TargetPlan> plans,
            java.util.List<ExecutionTargetRecord> sourceTargets
    ) {
        java.util.List<ExecutionTargetRecord> carried = sourceTargets.stream()
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
            java.util.List<TargetPlan> plans
    ) {
        saveDispatch(request, expectedTargets, plans, java.util.List.of());
    }

    private void saveDispatch(
            RemoteDispatchRequest request,
            int expectedTargets,
            java.util.List<TargetPlan> plans,
            java.util.List<ExecutionTargetRecord> carriedTargets
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
        java.util.List<ExecutionTargetRecord> plannedTargets = plans.stream().map(plan -> new ExecutionTargetRecord(
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
        java.util.List<ExecutionTargetRecord> carried = carriedTargets.stream().map(source ->
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
        executionRepository.saveTargets(java.util.stream.Stream.concat(
                plannedTargets.stream(), carried.stream()
        ).toList());
    }

    private String carriedTargetExecutionId(
            RemoteDispatchRequest request, ExecutionTargetRecord source
    ) {
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
                request,
                executionId,
                target.instanceId(),
                shardIndex,
                shardTotal
        )) + "\n");
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        resultPersistenceExecutor.shutdown();
        forwardingTransport.close();
        tlsContext.close();
        try {
            if (!resultPersistenceExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                resultPersistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resultPersistenceExecutor.shutdownNow();
        }
    }

    private NettyExecutorMessage triggerMessage(ExecutionCommand command) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("executionId", command.executionId());
        payload.put("rootExecutionId", command.rootExecutionId());
        payload.put("runAttempt", Integer.toString(command.runAttempt()));
        payload.put("jobId", command.definition().id());
        payload.put("handlerName", command.definition().handlerName());
        payload.put("scheduledFireTime", command.scheduledFireTime().toString());
        payload.put("dispatchTime", command.dispatchTime().toString());
        payload.put("ownerNodeId", command.ownerNodeId());
        payload.put("fencingToken", Long.toString(command.fencingToken()));
        command.definition().parameters().forEach((key, value) -> payload.put("param." + key, value));
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.TRIGGER_JOB,
                payload
        );
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
        if (shardIndex != null && shardTotal != null) {
            payload.put("param.firefly.shard.index", shardIndex.toString());
            payload.put("param.firefly.shard.total", shardTotal.toString());
        }
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.TRIGGER_JOB,
                payload
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
