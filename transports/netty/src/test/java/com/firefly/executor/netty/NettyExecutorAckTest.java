package com.firefly.executor.netty;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.executor.InMemoryExecutorRegistry;
import com.firefly.metrics.SchedulerMetrics;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyExecutorAckTest {
    @Test
    void completesParentOutboxOnlyAfterEveryExpectedTargetAcknowledges() {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord(
                "broadcast-1", "job-1", now, now, ExecutorDispatchMode.BROADCAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.DISPATCHED,
                2, 2, "node-a", 9, now, now
        ));
        executions.saveTargets(List.of(
                target("broadcast-1@instance:a", "a", now),
                target("broadcast-1@instance:b", "b", now)
        ));
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        AtomicInteger completedOutboxes = new AtomicInteger();
        SchedulerMetrics metrics = new SchedulerMetrics();
        EmbeddedChannel first = channel(
                "a", "session-a", connections, executions, clock, completedOutboxes, metrics
        );
        EmbeddedChannel second = channel(
                "b", "session-b", connections, executions, clock, completedOutboxes, metrics
        );

        first.writeInbound(ack("broadcast-1@instance:a", "a", "session-a"));
        assertEquals(0, completedOutboxes.get());

        second.writeInbound(ack("broadcast-1@instance:b", "b", "session-b"));
        assertEquals(1, completedOutboxes.get());
        first.writeInbound(ack("broadcast-1@instance:a", "a", "session-a"));
        assertEquals(2, metrics.snapshot().acknowledgementDelay().count());

        first.finishAndReleaseAll();
        second.finishAndReleaseAll();
    }

    private EmbeddedChannel channel(
            String instanceId,
            String sessionId,
            NettyExecutorConnectionRegistry connections,
            InMemoryExecutionRepository executions,
            Clock clock,
            AtomicInteger completedOutboxes,
            SchedulerMetrics metrics
    ) {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyExecutorGatewayHandler(
                new InMemoryExecutorRegistry(), connections, new NettyExecutorJsonCodec(), clock,
                new InMemorySchedulerCatalog(), true, "gateway-test", executions,
                (executionId, acknowledgedAt) -> completedOutboxes.incrementAndGet(), Runnable::run, "",
                (executionId, timeout) -> { }, metrics
        ));
        connections.register("orders", instanceId, sessionId, channel);
        return channel;
    }

    private String ack(String targetExecutionId, String instanceId, String sessionId) {
        return new NettyExecutorJsonCodec().encode(new NettyExecutorMessage(
                "ack-" + instanceId,
                NettyExecutorMessageType.ACK_JOB,
                Map.of(
                        "executionId", targetExecutionId,
                        "parentExecutionId", "broadcast-1",
                        "instanceId", instanceId,
                        "sessionId", sessionId,
                        "ownerNodeId", "node-a",
                        "fencingToken", "9"
                )
        ));
    }

    private ExecutionTargetRecord target(String targetExecutionId, String instanceId, Instant now) {
        return new ExecutionTargetRecord(
                targetExecutionId, "broadcast-1", instanceId, "gateway-test", null,
                ExecutionStatus.DISPATCHED, 1, null, null, "", now, now
        );
    }
}
