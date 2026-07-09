package com.firefly.executor.netty;

import com.firefly.engine.ExecutionCommand;
import com.firefly.executor.ExecutorRegistry;
import com.firefly.executor.InMemoryExecutorRegistry;
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
    private final NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyExecutorGateway(int port) {
        this(port, new InMemoryExecutorRegistry(), new NettyExecutorConnectionRegistry(), Clock.systemUTC());
    }

    public NettyExecutorGateway(
            int port,
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            Clock clock
    ) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.port = port;
        this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
        this.connectionRegistry = Objects.requireNonNull(connectionRegistry, "connectionRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(64 * 1024))
                                .addLast(new StringDecoder(StandardCharsets.UTF_8))
                                .addLast(new StringEncoder(StandardCharsets.UTF_8))
                                .addLast(new NettyExecutorGatewayHandler(
                                        executorRegistry,
                                        connectionRegistry,
                                        codec,
                                        clock
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

    public ExecutorRegistry executorRegistry() {
        return executorRegistry;
    }

    public NettyExecutorConnectionRegistry connectionRegistry() {
        return connectionRegistry;
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    private NettyExecutorMessage triggerMessage(ExecutionCommand command) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("executionId", command.executionId());
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
