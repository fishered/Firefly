package com.firefly.executor.netty;

import com.firefly.metrics.SchedulerMetrics;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyExecutorClientResourceControlTest {
    private final NettyExecutorJsonCodec codec = new NettyExecutorJsonCodec();

    @Test
    void boundsQueueAndConcurrencyAndSendsOverloadAck() throws Exception {
        SchedulerMetrics metrics = new SchedulerMetrics();
        NettyExecutorWorkScheduler workScheduler = NettyExecutorWorkScheduler.owned(
                new NettyExecutorResourceOptions(1, 1, 1),
                metrics
        );
        InMemoryJobHandlerRegistry handlers = new InMemoryJobHandlerRegistry();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseHandlers = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        handlers.register("run", ignored -> {
            int active = running.incrementAndGet();
            maxRunning.accumulateAndGet(active, Math::max);
            firstStarted.countDown();
            try {
                assertTrue(releaseHandlers.await(3, TimeUnit.SECONDS));
                handled.incrementAndGet();
            } finally {
                running.decrementAndGet();
            }
        });
        EmbeddedChannel channel = new EmbeddedChannel(new NettyExecutorClientHandler(
                "orders", "instance-a", "session-a", "", "orders-service", Duration.ofSeconds(30),
                handlers, workScheduler, codec, Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC),
                new NettyExecutorExecutionRegistry(), ignored -> { }, ignored -> { }, (ignored, reason) -> { }
        ));
        try {
            drainOutbound(channel);

            channel.writeInbound(trigger("exec-1"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            channel.writeInbound(trigger("exec-2"));
            channel.writeInbound(trigger("exec-3"));

            List<NettyExecutorMessage> messages = drainOutbound(channel);
            List<NettyExecutorMessage> acks = messages.stream()
                    .filter(message -> message.type() == NettyExecutorMessageType.ACK_JOB)
                    .toList();
            NettyExecutorMessage overloadResult = messages.stream()
                    .filter(message -> message.type() == NettyExecutorMessageType.REPORT_RESULT)
                    .findFirst()
                    .orElseThrow();

            assertEquals(3, acks.size());
            assertEquals("true", acks.get(0).payload().get("accepted"));
            assertEquals("true", acks.get(1).payload().get("accepted"));
            assertEquals("false", acks.get(2).payload().get("accepted"));
            assertEquals("executor_overloaded", acks.get(2).payload().get("reason"));
            assertEquals("exec-3", overloadResult.payload().get("executionId"));
            assertEquals("FAILED", overloadResult.payload().get("status"));
            assertEquals("executor_overloaded", overloadResult.payload().get("errorMessage"));
            assertEquals(1, metrics.snapshot().executorOverloadAcks());
            assertEquals(1, metrics.snapshot().executorClientActiveExecutions());
            assertEquals(1, metrics.snapshot().executorClientQueuedExecutions());
            assertEquals(1, metrics.snapshot().executorClientMaxConcurrentExecutions());
            assertEquals(1, metrics.snapshot().executorClientQueueCapacity());
            assertEquals(1, maxRunning.get());

            releaseHandlers.countDown();
            await(() -> handled.get() == 2, Duration.ofSeconds(3));
            assertEquals(1, maxRunning.get());
        } finally {
            releaseHandlers.countDown();
            channel.finishAndReleaseAll();
            workScheduler.close();
        }
    }

    @Test
    void doesNotCloseUserProvidedWorkerPool() {
        var workers = Executors.newSingleThreadExecutor();
        try {
            NettyExecutorClient client = NettyExecutorClient.builder()
                    .executorName("orders")
                    .workerPool(workers)
                    .build();

            client.close();

            assertFalse(workers.isShutdown());
        } finally {
            workers.shutdownNow();
        }
    }

    private String trigger(String executionId) {
        Instant now = Instant.parse("2026-07-28T10:00:00Z");
        return codec.encode(new NettyExecutorMessage(
                "trigger-" + executionId,
                NettyExecutorMessageType.TRIGGER_JOB,
                Map.of(
                        "executionId", executionId,
                        "parentExecutionId", executionId,
                        "jobId", "job-1",
                        "handlerName", "run",
                        "scheduledFireTime", now.toString(),
                        "dispatchTime", now.toString(),
                        "ownerNodeId", "node-a",
                        "fencingToken", "3"
                )
        ));
    }

    private List<NettyExecutorMessage> drainOutbound(EmbeddedChannel channel) {
        List<NettyExecutorMessage> messages = new ArrayList<>();
        String frame;
        while ((frame = channel.readOutbound()) != null) {
            messages.add(codec.decode(frame.trim()));
        }
        return messages;
    }

    private void await(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not met before timeout");
    }
}
