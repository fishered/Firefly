package io.github.nishi.firefly.executor.netty;

import io.github.nishi.firefly.handler.JobHandler;
import io.github.nishi.firefly.registry.InMemoryJobHandlerRegistry;
import io.github.nishi.firefly.registry.JobHandlerRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final JobHandlerRegistry handlerRegistry;
    private final ExecutorService workerPool;
    private final Clock clock;
    private final NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();
    private EventLoopGroup group;
    private Channel channel;

    @lombok.Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
    private NettyExecutorClient(
            String schedulerHost,
            int schedulerPort,
            String executorName,
            String instanceId,
            String serviceName,
            Duration heartbeatInterval,
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
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (schedulerPort < 1 || schedulerPort > 65535) {
            throw new IllegalArgumentException("schedulerPort must be between 1 and 65535");
        }
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
    }

    public static Builder builder() {
        return newBuilder()
                .schedulerHost("127.0.0.1")
                .schedulerPort(9700)
                .instanceId(UUID.randomUUID().toString())
                .serviceName("firefly-executor")
                .heartbeatInterval(Duration.ofSeconds(10))
                .handlerRegistry(new InMemoryJobHandlerRegistry())
                .workerPool(Executors.newCachedThreadPool())
                .clock(Clock.systemUTC());
    }

    public NettyExecutorClient registerHandler(String handlerName, JobHandler handler) {
        handlerRegistry.register(handlerName, handler);
        return this;
    }

    public void start() throws InterruptedException {
        group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(64 * 1024))
                                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                                .addLast(new NettyExecutorClientHandler(
                                        executorName,
                                        instanceId,
                                        serviceName,
                                        heartbeatInterval,
                                        handlerRegistry,
                                        workerPool,
                                        codec,
                                        clock
                                ));
                    }
                });
        channel = bootstrap.connect(schedulerHost, schedulerPort).sync().channel();
    }

    public JobHandlerRegistry handlerRegistry() {
        return handlerRegistry;
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        workerPool.shutdownNow();
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
