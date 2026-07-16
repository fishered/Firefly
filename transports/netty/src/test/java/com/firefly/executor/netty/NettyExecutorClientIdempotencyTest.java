package com.firefly.executor.netty;

import com.firefly.registry.InMemoryJobHandlerRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorClientIdempotencyTest {
    @Test
    void executesTheSameTargetOnlyOnceAcrossGatewayConnections() throws Exception {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryJobHandlerRegistry handlers = new InMemoryJobHandlerRegistry();
        AtomicInteger invocations = new AtomicInteger();
        handlers.register("run", ignored -> invocations.incrementAndGet());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        NettyExecutorExecutionRegistry executions = new NettyExecutorExecutionRegistry();
        EmbeddedChannel first = channel("session-a", handlers, workers, executions, clock);
        EmbeddedChannel second = channel("session-b", handlers, workers, executions, clock);
        drainOutbound(first);
        drainOutbound(second);
        String trigger = trigger(now);

        first.writeInbound(trigger);
        second.writeInbound(trigger);
        await(() -> invocations.get() == 1, Duration.ofSeconds(2));

        assertEquals(1, invocations.get());
        first.finishAndReleaseAll();
        second.finishAndReleaseAll();
        workers.shutdownNow();
    }

    @Test
    void replaysACompletedResultFromTheConfiguredStoreAfterRegistryRestart(@TempDir java.nio.file.Path tempDir)
            throws Exception {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryJobHandlerRegistry handlers = new InMemoryJobHandlerRegistry();
        AtomicInteger invocations = new AtomicInteger();
        handlers.register("run", ignored -> invocations.incrementAndGet());
        ExecutorResultStore resultStore = new FileExecutorResultStore(tempDir, Duration.ofHours(1), clock);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        EmbeddedChannel first = channel(
                "session-a", handlers, workers, new NettyExecutorExecutionRegistry(resultStore), clock
        );
        drainOutbound(first);
        first.writeInbound(trigger(now));
        await(() -> resultStore.find("exec-1@instance:instance-a").isPresent(), Duration.ofSeconds(2));
        assertTrue(resultStore.find("exec-1@instance:instance-a").isPresent());
        first.finishAndReleaseAll();

        EmbeddedChannel afterRestart = channel(
                "session-b", handlers, workers,
                new NettyExecutorExecutionRegistry(
                        new FileExecutorResultStore(tempDir, Duration.ofHours(1), clock)
                ), clock
        );
        drainOutbound(afterRestart);
        afterRestart.writeInbound(trigger(now));
        Thread.sleep(50);

        assertEquals(1, invocations.get());
        afterRestart.finishAndReleaseAll();
        workers.shutdownNow();
    }

    private EmbeddedChannel channel(
            String sessionId,
            InMemoryJobHandlerRegistry handlers,
            ExecutorService workers,
            NettyExecutorExecutionRegistry executions,
            Clock clock
    ) {
        return new EmbeddedChannel(new NettyExecutorClientHandler(
                "orders", "instance-a", sessionId, "", "orders-service", Duration.ofSeconds(30),
                handlers, workers, new NettyExecutorJsonCodec(), clock, executions, ignored -> { }
        ));
    }

    private String trigger(Instant now) {
        return new NettyExecutorJsonCodec().encode(new NettyExecutorMessage(
                "trigger-1",
                NettyExecutorMessageType.TRIGGER_JOB,
                Map.of(
                        "executionId", "exec-1@instance:instance-a",
                        "parentExecutionId", "exec-1",
                        "jobId", "job-1",
                        "handlerName", "run",
                        "scheduledFireTime", now.toString(),
                        "dispatchTime", now.toString(),
                        "ownerNodeId", "node-a",
                        "fencingToken", "3"
                )
        ));
    }

    private void drainOutbound(EmbeddedChannel channel) {
        while (channel.readOutbound() != null) {
            // Registration frames are not part of this assertion.
        }
    }

    private void await(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
