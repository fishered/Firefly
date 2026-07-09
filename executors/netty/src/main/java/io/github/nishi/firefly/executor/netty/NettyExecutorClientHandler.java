package io.github.nishi.firefly.executor.netty;

import io.github.nishi.firefly.domain.ExecutionContext;
import io.github.nishi.firefly.handler.JobHandler;
import io.github.nishi.firefly.registry.JobHandlerRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class NettyExecutorClientHandler extends SimpleChannelInboundHandler<String> {
    private final String executorName;
    private final String instanceId;
    private final String serviceName;
    private final Duration heartbeatInterval;
    private final JobHandlerRegistry handlerRegistry;
    private final ExecutorService workerPool;
    private final NettyExecutorJsonCodec codec;
    private final Clock clock;

    NettyExecutorClientHandler(
            String executorName,
            String instanceId,
            String serviceName,
            Duration heartbeatInterval,
            JobHandlerRegistry handlerRegistry,
            ExecutorService workerPool,
            NettyExecutorJsonCodec codec,
            Clock clock
    ) {
        this.executorName = executorName;
        this.instanceId = instanceId;
        this.serviceName = serviceName;
        this.heartbeatInterval = heartbeatInterval;
        this.handlerRegistry = handlerRegistry;
        this.workerPool = workerPool;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        write(context, new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.REGISTER_EXECUTOR,
                Map.of("executorName", executorName, "instanceId", instanceId, "serviceName", serviceName)
        ));
        context.executor().scheduleAtFixedRate(
                () -> write(context, heartbeatMessage()),
                heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String frame) {
        NettyExecutorMessage message = codec.decode(frame.trim());
        if (message.type() != NettyExecutorMessageType.TRIGGER_JOB) {
            return;
        }
        write(context, ackMessage(message));
        workerPool.submit(() -> execute(context, message));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        write(context, new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.UNREGISTER_EXECUTOR,
                Map.of("executorName", executorName, "instanceId", instanceId)
        ));
    }

    private void execute(ChannelHandlerContext context, NettyExecutorMessage message) {
        Map<String, String> payload = message.payload();
        String handlerName = payload.get("handlerName");
        JobHandler handler = handlerRegistry.find(handlerName)
                .orElseThrow(() -> new IllegalStateException("handler not found: " + handlerName));
        Instant actualFireTime = clock.instant();
        ExecutionContext executionContext = new ExecutionContext(
                payload.get("executionId"),
                payload.get("jobId"),
                handlerName,
                Instant.parse(payload.get("scheduledFireTime")),
                Instant.parse(payload.get("dispatchTime")),
                actualFireTime,
                parameters(payload)
        );
        try {
            handler.handle(executionContext);
            write(context, resultMessage(message, "SUCCEEDED", ""));
        } catch (Exception e) {
            write(context, resultMessage(message, "FAILED", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    private NettyExecutorMessage heartbeatMessage() {
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.HEARTBEAT,
                Map.of("executorName", executorName, "instanceId", instanceId)
        );
    }

    private NettyExecutorMessage ackMessage(NettyExecutorMessage trigger) {
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.ACK_JOB,
                Map.of("triggerMessageId", trigger.messageId(), "executionId", trigger.payload().get("executionId"))
        );
    }

    private NettyExecutorMessage resultMessage(NettyExecutorMessage trigger, String status, String errorMessage) {
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.REPORT_RESULT,
                Map.of(
                        "triggerMessageId", trigger.messageId(),
                        "executionId", trigger.payload().get("executionId"),
                        "status", status,
                        "errorMessage", errorMessage
                )
        );
    }

    private Map<String, String> parameters(Map<String, String> payload) {
        return payload.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("param."))
                .collect(Collectors.toMap(
                        entry -> entry.getKey().substring("param.".length()),
                        Map.Entry::getValue
                ));
    }

    private void write(ChannelHandlerContext context, NettyExecutorMessage message) {
        context.writeAndFlush(codec.encode(message) + "\n");
    }
}
