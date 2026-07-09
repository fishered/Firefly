package com.firefly.executor.netty;

import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.executor.ExecutorRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

final class NettyExecutorGatewayHandler extends SimpleChannelInboundHandler<String> {
    private final ExecutorRegistry executorRegistry;
    private final NettyExecutorConnectionRegistry connectionRegistry;
    private final NettyExecutorJsonCodec codec;
    private final Clock clock;

    NettyExecutorGatewayHandler(
            ExecutorRegistry executorRegistry,
            NettyExecutorConnectionRegistry connectionRegistry,
            NettyExecutorJsonCodec codec,
            Clock clock
    ) {
        this.executorRegistry = executorRegistry;
        this.connectionRegistry = connectionRegistry;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String frame) {
        NettyExecutorMessage message = codec.decode(frame.trim());
        switch (message.type()) {
            case REGISTER_EXECUTOR -> register(context, message.payload());
            case HEARTBEAT -> heartbeat(message.payload());
            case UNREGISTER_EXECUTOR -> unregister(context, message.payload());
            case ACK_JOB, REPORT_RESULT -> {
                // Execution result persistence will be wired when execution history lands.
            }
            case TRIGGER_JOB -> throw new IllegalStateException("gateway must not receive TRIGGER_JOB");
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
        String serviceName = payload.getOrDefault("serviceName", instanceId);
        Instant now = clock.instant();
        executorRegistry.register(ExecutorInstance.builder()
                .executorName(executorName)
                .instanceId(instanceId)
                .serviceName(serviceName)
                .host(remoteHost(context))
                .port(0)
                .protocol(ExecutorProtocol.TCP)
                .registeredAt(now)
                .lastHeartbeatAt(now)
                .build());
        connectionRegistry.register(executorName, instanceId, context.channel());
    }

    private void heartbeat(Map<String, String> payload) {
        executorRegistry.heartbeat(payload.get("executorName"), payload.get("instanceId"), clock.instant());
    }

    private void unregister(ChannelHandlerContext context, Map<String, String> payload) {
        executorRegistry.markOffline(payload.get("executorName"), payload.get("instanceId"));
        connectionRegistry.unregister(context.channel());
    }

    private String remoteHost(ChannelHandlerContext context) {
        if (context.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getHostString();
        }
        return "unknown";
    }
}
