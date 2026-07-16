package com.firefly.executor.netty;

import com.firefly.domain.ExecutionContext;
import com.firefly.handler.JobHandler;
import com.firefly.registry.JobHandlerRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class NettyExecutorClientHandler extends SimpleChannelInboundHandler<String> {
    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(
            NettyExecutorClientHandler.class.getName()
    );
    private final String executorName;
    private final String instanceId;
    private final String sessionId;
    private final String authToken;
    private final String serviceName;
    private final Duration heartbeatInterval;
    private final JobHandlerRegistry handlerRegistry;
    private final ExecutorService workerPool;
    private final NettyExecutorJsonCodec codec;
    private final Clock clock;
    private final NettyExecutorExecutionRegistry executionRegistry;
    private final Consumer<io.netty.channel.Channel> disconnectListener;
    private io.netty.util.concurrent.ScheduledFuture<?> heartbeatTask;

    NettyExecutorClientHandler(
            String executorName,
            String instanceId,
            String sessionId,
            String authToken,
            String serviceName,
            Duration heartbeatInterval,
            JobHandlerRegistry handlerRegistry,
            ExecutorService workerPool,
            NettyExecutorJsonCodec codec,
            Clock clock,
            NettyExecutorExecutionRegistry executionRegistry,
            Consumer<io.netty.channel.Channel> disconnectListener
    ) {
        this.executorName = executorName;
        this.instanceId = instanceId;
        this.sessionId = sessionId;
        this.authToken = authToken;
        this.serviceName = serviceName;
        this.heartbeatInterval = heartbeatInterval;
        this.handlerRegistry = handlerRegistry;
        this.workerPool = workerPool;
        this.codec = codec;
        this.clock = clock;
        this.executionRegistry = executionRegistry;
        this.disconnectListener = disconnectListener;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        write(context, new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.REGISTER_EXECUTOR,
                Map.of(
                        "executorName", executorName,
                        "instanceId", instanceId,
                        "sessionId", sessionId,
                        "authToken", authToken,
                        "serviceName", serviceName,
                        "protocolVersion", Integer.toString(NettyExecutorProtocol.CURRENT_VERSION),
                        "capabilities", NettyExecutorProtocol.encodeCapabilities(
                                NettyExecutorProtocol.CLIENT_CAPABILITIES
                        )
                )
        ));
        heartbeatTask = context.executor().scheduleAtFixedRate(
                () -> {
                    if (context.channel().isActive()) {
                        write(context, heartbeatMessage());
                    }
                },
                heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, String frame) {
        NettyExecutorMessage message = codec.decode(frame.trim());
        if (message.type() == NettyExecutorMessageType.REGISTER_REJECTED) {
            log.warning("executor registration rejected: " + message.payload().getOrDefault("reason", "unknown"));
            context.close();
            return;
        }
        if (message.type() == NettyExecutorMessageType.REGISTERED) {
            if (!validRegistrationResponse(message.payload())) {
                log.warning("executor registration response has an incompatible protocol contract");
                context.close();
            }
            return;
        }
        if (message.type() != NettyExecutorMessageType.TRIGGER_JOB) {
            return;
        }
        write(context, ackMessage(message));
        String executionId = message.payload().get("executionId");
        NettyExecutorExecutionRegistry.ExecutionClaim claim = executionRegistry.claim(executionId);
        if (!claim.owner()) {
            claim.execution().thenAccept(result ->
                    write(context, resultMessage(message, result.status(), result.errorMessage()))
            );
            return;
        }
        try {
            workerPool.submit(() -> {
                ExecutorExecutionResult result = execute(message);
                executionRegistry.complete(executionId, claim.execution(), result);
                write(context, resultMessage(message, result.status(), result.errorMessage()));
                context.executor().schedule(
                        () -> executionRegistry.remove(executionId, claim.execution()), 1, TimeUnit.HOURS
                );
            });
        } catch (RuntimeException e) {
            ExecutorExecutionResult result = new ExecutorExecutionResult(
                    "FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            );
            executionRegistry.complete(executionId, claim.execution(), result);
            write(context, resultMessage(message, result.status(), result.errorMessage()));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        disconnectListener.accept(context.channel());
    }

    private ExecutorExecutionResult execute(NettyExecutorMessage message) {
        try {
            Map<String, String> payload = message.payload();
            String handlerName = payload.get("handlerName");
            JobHandler handler = handlerRegistry.find(handlerName)
                    .orElseThrow(() -> new IllegalStateException("handler not found: " + handlerName));
            Instant actualFireTime = clock.instant();
            ExecutionContext executionContext = new ExecutionContext(
                    payload.get("executionId"),
                    payload.getOrDefault("rootExecutionId", payload.get("executionId")),
                    Integer.parseInt(payload.getOrDefault("runAttempt", "0")),
                    payload.get("jobId"),
                    handlerName,
                    Instant.parse(payload.get("scheduledFireTime")),
                    Instant.parse(payload.get("dispatchTime")),
                    actualFireTime,
                    parameters(payload)
            );
            handler.handle(executionContext);
            return new ExecutorExecutionResult("SUCCEEDED", "");
        } catch (Exception e) {
            return new ExecutorExecutionResult(
                    "FAILED", e.getMessage() == null ? "" : e.getMessage()
            );
        }
    }

    private NettyExecutorMessage heartbeatMessage() {
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.HEARTBEAT,
                Map.of("executorName", executorName, "instanceId", instanceId, "sessionId", sessionId)
        );
    }

    private NettyExecutorMessage ackMessage(NettyExecutorMessage trigger) {
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.ACK_JOB,
                Map.of(
                        "triggerMessageId", trigger.messageId(),
                        "executionId", trigger.payload().get("executionId"),
                        "parentExecutionId", trigger.payload().getOrDefault(
                                "parentExecutionId", trigger.payload().get("executionId")
                        ),
                        "instanceId", instanceId,
                        "sessionId", sessionId
                        , "ownerNodeId", trigger.payload().getOrDefault("ownerNodeId", "local")
                        , "fencingToken", trigger.payload().getOrDefault("fencingToken", "1")
                )
        );
    }

    private NettyExecutorMessage resultMessage(NettyExecutorMessage trigger, String status, String errorMessage) {
        return new NettyExecutorMessage(
                UUID.randomUUID().toString(),
                NettyExecutorMessageType.REPORT_RESULT,
                Map.of(
                        "triggerMessageId", trigger.messageId(),
                        "executionId", trigger.payload().get("executionId"),
                        "parentExecutionId", trigger.payload().getOrDefault(
                                "parentExecutionId", trigger.payload().get("executionId")
                        ),
                        "instanceId", instanceId,
                        "sessionId", sessionId,
                        "ownerNodeId", trigger.payload().getOrDefault("ownerNodeId", "local"),
                        "fencingToken", trigger.payload().getOrDefault("fencingToken", "1"),
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

    private boolean validRegistrationResponse(Map<String, String> payload) {
        try {
            int version = Integer.parseInt(payload.getOrDefault("protocolVersion", ""));
            return NettyExecutorProtocol.supports(version)
                    && NettyExecutorProtocol.hasRequiredCapabilities(
                    NettyExecutorProtocol.decodeCapabilities(payload.get("capabilities"))
            );
        } catch (NumberFormatException invalidVersion) {
            return false;
        }
    }
}
