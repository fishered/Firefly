package com.firefly.executor.netty;

import com.firefly.handler.JobHandler;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import com.firefly.registry.JobHandlerRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Business-side executor client that connects outward to the Firefly scheduler gateway.
 */
public final class NettyExecutorClient implements AutoCloseable {
    private final String schedulerHost;
    private final int schedulerPort;
    private final String executorName;
    private final String instanceId;
    private final String serviceName;
    private final Duration heartbeatInterval;
    private final List<GatewayEndpoint> gatewayEndpoints;
    private final Duration reconnectInitialDelay;
    private final Duration reconnectMaxDelay;
    private final String authToken;
    private final JobHandlerRegistry handlerRegistry;
    private final ExecutorService workerPool;
    private final Clock clock;
    private final NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();
    private final NettyExecutorExecutionRegistry executionRegistry;
    private final io.netty.handler.ssl.SslContext sslContext;
    private EventLoopGroup group;
    private final Map<GatewayEndpoint, Channel> channels = new ConcurrentHashMap<>();
    private final Map<GatewayEndpoint, AtomicInteger> reconnectAttempts = new ConcurrentHashMap<>();
    private final Set<GatewayEndpoint> connecting = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closing = new AtomicBoolean();
    private Bootstrap bootstrap;

    @lombok.Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
    private NettyExecutorClient(
            String schedulerHost,
            int schedulerPort,
            String executorName,
            String instanceId,
            String serviceName,
            Duration heartbeatInterval,
            List<String> gatewayAddresses,
            Duration reconnectInitialDelay,
            Duration reconnectMaxDelay,
            String authToken,
            NettyTlsOptions tlsOptions,
            ExecutorResultStore resultStore,
            JobHandlerRegistry handlerRegistry,
            ExecutorService workerPool,
            Clock clock
    ) {
        this.schedulerHost = requireNonBlank(schedulerHost, "schedulerHost");
        this.schedulerPort = schedulerPort;
        this.executorName = requireNonBlank(executorName, "executorName");
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        this.serviceName = requireNonBlank(serviceName, "serviceName");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.gatewayEndpoints = endpoints(gatewayAddresses, this.schedulerHost, this.schedulerPort);
        this.reconnectInitialDelay = Objects.requireNonNull(reconnectInitialDelay, "reconnectInitialDelay");
        this.reconnectMaxDelay = Objects.requireNonNull(reconnectMaxDelay, "reconnectMaxDelay");
        this.authToken = authToken == null ? "" : authToken;
        this.sslContext = Objects.requireNonNull(tlsOptions, "tlsOptions").clientContext();
        this.executionRegistry = new NettyExecutorExecutionRegistry(
                Objects.requireNonNull(resultStore, "resultStore")
        );
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (schedulerPort < 1 || schedulerPort > 65535) {
            throw new IllegalArgumentException("schedulerPort must be between 1 and 65535");
        }
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        if (reconnectInitialDelay.isNegative() || reconnectMaxDelay.compareTo(reconnectInitialDelay) < 0) {
            throw new IllegalArgumentException("invalid reconnect delay range");
        }
    }

    public static Builder builder() {
        return newBuilder()
                .schedulerHost("127.0.0.1")
                .schedulerPort(9700)
                .instanceId(UUID.randomUUID().toString())
                .serviceName("firefly-executor")
                .heartbeatInterval(Duration.ofSeconds(10))
                .gatewayAddresses(List.of())
                .reconnectInitialDelay(Duration.ofSeconds(1))
                .reconnectMaxDelay(Duration.ofSeconds(30))
                .authToken("")
                .tlsOptions(NettyTlsOptions.disabled())
                .resultStore(new InMemoryExecutorResultStore())
                .handlerRegistry(new InMemoryJobHandlerRegistry())
                .workerPool(Executors.newCachedThreadPool())
                .clock(Clock.systemUTC());
    }

    public NettyExecutorClient registerHandler(String handlerName, JobHandler handler) {
        handlerRegistry.register(handlerName, handler);
        return this;
    }

    public void start() throws InterruptedException {
        if (group != null) {
            return;
        }
        closing.set(false);
        group = new NioEventLoopGroup(1);
        bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        String sessionId = UUID.randomUUID().toString();
                        if (sslContext != null) {
                            java.net.InetSocketAddress remote = (java.net.InetSocketAddress) channel.remoteAddress();
                            channel.pipeline().addLast(
                                    "tls", sslContext.newHandler(channel.alloc(), remote.getHostString(), remote.getPort())
                            );
                        }
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(64 * 1024))
                                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                                .addLast(new NettyExecutorClientHandler(
                                        executorName,
                                        instanceId,
                                        sessionId,
                                        authToken,
                                        serviceName,
                                        heartbeatInterval,
                                        handlerRegistry,
                                        workerPool,
                                        codec,
                                        clock,
                                        executionRegistry,
                                        NettyExecutorClient.this::disconnected
                                ));
                    }
                });
        for (GatewayEndpoint endpoint : gatewayEndpoints) {
            connect(endpoint, true);
        }
    }

    public JobHandlerRegistry handlerRegistry() {
        return handlerRegistry;
    }

    public int connectedGatewayCount() {
        return (int) channels.values().stream().filter(Channel::isActive).count();
    }

    @Override
    public void close() {
        closing.set(true);
        channels.values().forEach(Channel::close);
        channels.clear();
        if (group != null) {
            group.shutdownGracefully();
        }
        workerPool.shutdownNow();
    }

    private void connect(GatewayEndpoint endpoint, boolean await) throws InterruptedException {
        if (closing.get() || !connecting.add(endpoint)) {
            return;
        }
        ChannelFuture future = bootstrap.connect(endpoint.host(), endpoint.port());
        future.addListener(completed -> {
            connecting.remove(endpoint);
            if (completed.isSuccess()) {
                channels.put(endpoint, future.channel());
                reconnectAttempts.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).set(0);
            } else {
                scheduleReconnect(endpoint);
            }
        });
        if (await) {
            future.await();
        }
    }

    private void disconnected(Channel disconnectedChannel) {
        channels.entrySet().stream()
                .filter(entry -> entry.getValue().equals(disconnectedChannel))
                .findFirst()
                .ifPresent(entry -> {
                    if (channels.remove(entry.getKey(), disconnectedChannel)) {
                        scheduleReconnect(entry.getKey());
                    }
                });
    }

    private void scheduleReconnect(GatewayEndpoint endpoint) {
        if (closing.get() || group == null || group.isShuttingDown()) {
            return;
        }
        int attempt = reconnectAttempts.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).getAndIncrement();
        long initialMillis = reconnectInitialDelay.toMillis();
        long maximumMillis = reconnectMaxDelay.toMillis();
        long exponential = initialMillis * (1L << Math.min(attempt, 20));
        long capped = Math.min(maximumMillis, exponential);
        long jitter = capped == 0 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, capped / 5));
        group.next().schedule(() -> {
            try {
                connect(endpoint, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, capped + jitter, TimeUnit.MILLISECONDS);
    }

    private static List<GatewayEndpoint> endpoints(List<String> addresses, String fallbackHost, int fallbackPort) {
        List<String> configured = addresses == null ? List.of() : addresses;
        if (configured.isEmpty()) {
            return List.of(new GatewayEndpoint(fallbackHost, fallbackPort));
        }
        List<GatewayEndpoint> endpoints = new ArrayList<>();
        for (String address : configured) {
            int separator = address.lastIndexOf(':');
            if (separator < 1 || separator == address.length() - 1) {
                throw new IllegalArgumentException("gateway address must be host:port: " + address);
            }
            endpoints.add(new GatewayEndpoint(
                    requireNonBlank(address.substring(0, separator), "gateway host"),
                    Integer.parseInt(address.substring(separator + 1))
            ));
        }
        return List.copyOf(endpoints);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record GatewayEndpoint(String host, int port) {
        private GatewayEndpoint {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("gateway port must be between 1 and 65535");
            }
        }
    }
}
