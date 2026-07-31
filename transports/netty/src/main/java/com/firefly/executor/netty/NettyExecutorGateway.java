package com.firefly.executor.netty;

import com.firefly.engine.ExecutionCommand;
import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.catalog.SchedulerCatalog;
import com.firefly.domain.ExecutionContext;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRoutingStrategy;
import com.firefly.executor.ExecutorRegistry;
import com.firefly.executor.InMemoryExecutorRegistry;
import com.firefly.executor.ExecutorInstanceDirectory;
import com.firefly.executor.ExecutorInstanceLocation;
import com.firefly.executor.InMemoryExecutorInstanceDirectory;
import com.firefly.executor.RemoteDispatchRequest;
import com.firefly.executor.RemoteDispatchResult;
import com.firefly.executor.RemoteExecutorTransport;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionRepository;
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
public final class NettyExecutorGateway implements RemoteExecutorTransport {
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
    private volatile java.util.function.BiPredicate<String, String> registrationAuthenticator;
    private final java.util.function.BiConsumer<String, Boolean> retryScheduler;
    private final SchedulerMetrics metrics;
    private final NettyExecutorGatewayOptions options;
    private final NettyResultPersistenceExecutor resultPersistenceExecutor;
    private final ReloadingNettyTlsContext tlsContext;
    private final ExecutorInstanceDirectory instanceDirectory;
    private final NettyGatewayForwardingTransport forwardingTransport;
    private final NettyExecutorDispatchService dispatchService;
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
        this.registrationAuthenticator = sharedTokenAuthenticator(this.executorAuthToken);
        this.retryScheduler = Objects.requireNonNull(retryScheduler, "retryScheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.options = Objects.requireNonNull(options, "options");
        this.tlsContext = new ReloadingNettyTlsContext(options.tls(), options.tlsReloadInterval());
        this.instanceDirectory = Objects.requireNonNull(instanceDirectory, "instanceDirectory");
        this.forwardingTransport = new NettyGatewayForwardingTransport(connectionRegistry, options, metrics);
        this.dispatchService = new NettyExecutorDispatchService(
                schedulerCatalog, connectionRegistry, instanceDirectory, clock, executionRepository,
                gatewayNodeId, forwardingTransport, codec
        );
        this.resultPersistenceExecutor = new NettyResultPersistenceExecutor(options.resultQueueCapacity());
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
                                        registrationAuthenticator,
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

    public void setRegistrationAuthenticator(java.util.function.BiPredicate<String, String> authenticator) {
        this.registrationAuthenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    private static java.util.function.BiPredicate<String, String> sharedTokenAuthenticator(String expected) {
        return (provided, executorName) -> expected.isBlank() || java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8)
        );
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
        return dispatchService.dispatch(request);
    }

    public ExecutorRegistry executorRegistry() {
        return executorRegistry;
    }

    public NettyExecutorConnectionRegistry connectionRegistry() {
        return connectionRegistry;
    }

    public boolean hasRoute(String executorName) {
        return dispatchService.hasRoute(executorName);
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
        forwardingTransport.close();
        tlsContext.close();
        resultPersistenceExecutor.close();
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

}
