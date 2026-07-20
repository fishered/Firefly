package com.firefly.executor.netty;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.executor.InMemoryExecutorRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyExecutorCancellationTest {
    @Test
    void gatewaySendsCancellationToTheConnectedTargetInstance() {
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord(
                "cancel-parent", "job-1", now, now, ExecutorDispatchMode.UNICAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.RUNNING,
                1, 1, "scheduler-a", 9L, now, now
        ));
        executions.saveTargets(List.of(new ExecutionTargetRecord(
                "cancel-target", "cancel-parent", "instance-a", "gateway-a", null,
                ExecutionStatus.RUNNING, 1, now, null, "", now, now
        )));
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        EmbeddedChannel channel = new EmbeddedChannel();
        connections.register("orders", "instance-a", "session-a", channel);
        NettyExecutorGateway gateway = new NettyExecutorGateway(
                0, new InMemoryExecutorRegistry(), connections, Clock.fixed(now, java.time.ZoneOffset.UTC),
                new InMemorySchedulerCatalog(), true, "gateway-a", executions
        );

        assertEquals(1, gateway.cancel("cancel-parent", "maintenance"));
        NettyExecutorMessage message = new NettyExecutorJsonCodec().decode(
                ((String) channel.readOutbound()).trim()
        );
        assertEquals(NettyExecutorMessageType.CANCEL_JOB, message.type());
        assertEquals("cancel-target", message.payload().get("executionId"));
        assertEquals("maintenance", message.payload().get("reason"));
        channel.finishAndReleaseAll();
        gateway.close();
    }
}
