package com.firefly.executor.netty;

import com.firefly.domain.ExecutionContext;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRoutingStrategy;
import com.firefly.executor.InMemoryExecutorRegistry;
import com.firefly.executor.RemoteDispatchRequest;
import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.execution.ExecutionStatus;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorDispatchTest {
    private final NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();

    @Test
    void unicastSendsOneMessageWhileBroadcastSendsOneChildPerInstance() {
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        connections.register("orders", "orders-1", first);
        connections.register("orders", "orders-2", second);
        NettyExecutorGateway gateway = gateway(connections);

        var unicast = gateway.dispatch(request(ExecutorDispatchMode.UNICAST, 1));
        assertEquals(1, unicast.acceptedTargets());
        assertEquals(1, outboundCount(first) + outboundCount(second));

        NettyExecutorGateway broadcastGateway = gateway(connections);
        var broadcast = broadcastGateway.dispatch(request(ExecutorDispatchMode.BROADCAST, 1));
        assertEquals(2, broadcast.acceptedTargets());
        NettyExecutorMessage firstMessage = read(first);
        NettyExecutorMessage secondMessage = read(second);
        assertTrue(firstMessage.payload().get("executionId").contains("@instance:"));
        assertTrue(secondMessage.payload().get("executionId").contains("@instance:"));

        EmbeddedChannel late = new EmbeddedChannel();
        connections.register("orders", "orders-3", late);
        var retry = broadcastGateway.dispatch(request(ExecutorDispatchMode.BROADCAST, 1));
        assertEquals(2, retry.requestedTargets());
        assertEquals(2, retry.acceptedTargets());
        assertEquals(2, outboundCount(first) + outboundCount(second));
        assertEquals(0, outboundCount(late));
    }

    @Test
    void shardingCreatesOneChildExecutionPerShard() {
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        connections.register("orders", "orders-1", first);
        connections.register("orders", "orders-2", second);
        NettyExecutorGateway gateway = gateway(connections);

        var result = gateway.dispatch(request(ExecutorDispatchMode.SHARDING, 4));

        assertEquals(4, result.requestedTargets());
        assertEquals(4, result.acceptedTargets());
        assertEquals(4, outboundCount(first) + outboundCount(second));
    }

    @Test
    void persistsParentAndTargetRecordsBeforeSending() {
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        connections.register("orders", "orders-1", new EmbeddedChannel());
        connections.register("orders", "orders-2", new EmbeddedChannel());
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        NettyExecutorGateway gateway = gateway(connections, executions);

        gateway.dispatch(request(ExecutorDispatchMode.BROADCAST, 1));

        assertEquals(2, executions.findExecution("exec-1").orElseThrow().acceptedTargets());
        assertEquals(2, executions.listTargets("exec-1").size());
    }

    @Test
    void broadcastRetryDispatchesOnlyFailedTargets() {
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        connections.register("orders", "orders-1", first);
        connections.register("orders", "orders-2", second);
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        NettyExecutorGateway gateway = gateway(connections, executions);
        Instant completedAt = Instant.parse("2026-07-14T10:00:01Z");

        gateway.dispatch(request(ExecutorDispatchMode.BROADCAST, 1));
        outboundCount(first);
        outboundCount(second);
        var targets = executions.listTargets("exec-1");
        executions.completeResult(targets.get(0).targetExecutionId(), ExecutionStatus.SUCCEEDED, "", completedAt);
        executions.completeResult(targets.get(1).targetExecutionId(), ExecutionStatus.FAILED, "boom", completedAt);

        var retry = gateway.dispatch(retryRequest(ExecutorDispatchMode.BROADCAST, 1));

        assertEquals(1, retry.requestedTargets());
        assertEquals(1, retry.acceptedTargets());
        assertEquals(1, outboundCount(first) + outboundCount(second));
    }

    @Test
    void shardingRetrySkipsSuccessfulAndPreviouslyMissingShards() {
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        connections.register("orders", "orders-1", first);
        connections.register("orders", "orders-2", second);
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        NettyExecutorGateway gateway = gateway(connections, executions);
        Instant completedAt = Instant.parse("2026-07-14T10:00:01Z");

        gateway.dispatch(request(ExecutorDispatchMode.SHARDING, 4));
        outboundCount(first);
        outboundCount(second);
        executions.listTargets("exec-1").forEach(target -> executions.completeResult(
                target.targetExecutionId(), target.shardIndex() < 2
                        ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                "", completedAt
        ));

        var retry = gateway.dispatch(retryRequest(ExecutorDispatchMode.SHARDING, 4));

        assertEquals(2, retry.requestedTargets());
        assertEquals(2, retry.acceptedTargets());
        assertEquals(2, outboundCount(first) + outboundCount(second));
    }

    @Test
    void quorumRetryCarriesPreviousSuccessesWithoutReexecutingThem() {
        NettyExecutorConnectionRegistry connections = new NettyExecutorConnectionRegistry();
        java.util.List<EmbeddedChannel> channels = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            EmbeddedChannel channel = new EmbeddedChannel();
            channels.add(channel);
            connections.register("orders", "orders-" + index, channel);
        }
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        NettyExecutorGateway gateway = gateway(connections, executions);
        Instant completedAt = Instant.parse("2026-07-14T10:00:01Z");

        gateway.dispatch(request(ExecutorDispatchMode.BROADCAST, 1, ExecutorCompletionPolicy.QUORUM));
        channels.forEach(this::outboundCount);
        var sourceTargets = executions.listTargets("exec-1");
        for (int index = 0; index < sourceTargets.size(); index++) {
            executions.completeResult(
                    sourceTargets.get(index).targetExecutionId(),
                    index < 2 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                    "", completedAt
            );
        }

        var retry = gateway.dispatch(retryRequest(
                ExecutorDispatchMode.BROADCAST, 1, ExecutorCompletionPolicy.QUORUM
        ));
        assertEquals(3, retry.requestedTargets());
        assertEquals(3, retry.acceptedTargets());
        assertEquals(3, channels.stream().mapToInt(this::outboundCount).sum());
        assertEquals(5, executions.listTargets("exec-1@attempt:1").size());

        var retriedTarget = executions.listTargets("exec-1@attempt:1").stream()
                .filter(target -> target.status() == ExecutionStatus.DISPATCHED)
                .findFirst().orElseThrow();
        executions.completeResult(
                retriedTarget.targetExecutionId(), ExecutionStatus.SUCCEEDED, "", completedAt.plusSeconds(1)
        );
        assertEquals(ExecutionStatus.SUCCEEDED,
                executions.findExecution("exec-1@attempt:1").orElseThrow().status());
    }

    private NettyExecutorGateway gateway(NettyExecutorConnectionRegistry connections) {
        return gateway(connections, new InMemoryExecutionRepository());
    }

    private NettyExecutorGateway gateway(
            NettyExecutorConnectionRegistry connections,
            InMemoryExecutionRepository executions
    ) {
        return new NettyExecutorGateway(
                9700,
                new InMemoryExecutorRegistry(),
                connections,
                Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC),
                new InMemorySchedulerCatalog(),
                true,
                "gateway-test",
                executions
        );
    }

    private RemoteDispatchRequest request(ExecutorDispatchMode mode, int shardCount) {
        return request(mode, shardCount, ExecutorCompletionPolicy.ALL_SUCCESS);
    }

    private RemoteDispatchRequest request(
            ExecutorDispatchMode mode, int shardCount, ExecutorCompletionPolicy completionPolicy
    ) {
        Instant now = Instant.parse("2026-07-14T10:00:00Z");
        return new RemoteDispatchRequest(
                "orders",
                "handleOrder",
                new ExecutionContext("exec-1", "job-1", "remote:orders:handleOrder", now, now, now, Map.of()),
                mode,
                ExecutorRoutingStrategy.ROUND_ROBIN,
                completionPolicy,
                shardCount,
                "order-42"
        );
    }

    private RemoteDispatchRequest retryRequest(ExecutorDispatchMode mode, int shardCount) {
        return retryRequest(mode, shardCount, ExecutorCompletionPolicy.ALL_SUCCESS);
    }

    private RemoteDispatchRequest retryRequest(
            ExecutorDispatchMode mode, int shardCount, ExecutorCompletionPolicy completionPolicy
    ) {
        Instant now = Instant.parse("2026-07-14T10:00:02Z");
        return new RemoteDispatchRequest(
                "orders", "handleOrder",
                new ExecutionContext(
                        "exec-1@attempt:1", "exec-1", 1, "job-1",
                        "remote:orders:handleOrder", now.minusSeconds(2), now, now, Map.of()
                ),
                mode, ExecutorRoutingStrategy.ROUND_ROBIN, completionPolicy,
                shardCount, "order-42", "local", 1L, "exec-1", 1
        );
    }

    private int outboundCount(EmbeddedChannel channel) {
        int count = 0;
        while (channel.readOutbound() != null) {
            count++;
        }
        return count;
    }

    private NettyExecutorMessage read(EmbeddedChannel channel) {
        String frame = channel.readOutbound();
        return codec.decode(frame.trim());
    }
}
