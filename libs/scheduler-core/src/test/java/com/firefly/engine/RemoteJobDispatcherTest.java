package com.firefly.engine;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRoutingStrategy;
import com.firefly.domain.JobDefinition;
import com.firefly.executor.RemoteDispatchRequest;
import com.firefly.executor.RemoteDispatchResult;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteJobDispatcherTest {
    @Test
    void buildsTransportNeutralDispatchPlanFromThePersistedJobDefinition() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<RemoteDispatchRequest> captured = new AtomicReference<>();
            CountDownLatch dispatched = new CountDownLatch(1);
            JobDispatcher dispatcher = new JobDispatcher(
                    new InMemoryJobHandlerRegistry(),
                    workers,
                    Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC),
                    request -> {
                        captured.set(request);
                        dispatched.countDown();
                        return new RemoteDispatchResult(2, 2, List.of("orders-1", "orders-2"));
                    }
            );
            JobDefinition definition = JobDefinition.builder()
                    .id("broadcast-orders")
                    .name("Broadcast orders")
                    .handlerName("remote:orders:refreshCache")
                    .schedule(new CronSchedule("0 * * * * *"))
                    .parameters(Map.of("executorName", "orders", "handlerName", "refreshCache"))
                    .dispatchMode(ExecutorDispatchMode.BROADCAST)
                    .routingStrategy(ExecutorRoutingStrategy.CONSISTENT_HASH)
                    .completionPolicy(ExecutorCompletionPolicy.ALL_SUCCESS)
                    .build();

            dispatcher.dispatch(definition, Instant.parse("2026-07-14T10:00:00Z"));

            assertTrue(dispatched.await(2, TimeUnit.SECONDS));
            assertEquals("orders", captured.get().executorName());
            assertEquals(ExecutorDispatchMode.BROADCAST, captured.get().dispatchMode());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void transportRejectionDoesNotMakeTheExecutionTerminalBeforeOutboxRedelivery() {
        var workers = Executors.newSingleThreadExecutor();
        try {
            Instant now = Instant.parse("2026-07-18T10:00:00Z");
            InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
            AtomicInteger submissions = new AtomicInteger();
            JobDispatcher dispatcher = new JobDispatcher(
                    new InMemoryJobHandlerRegistry(), workers, Clock.fixed(now, ZoneOffset.UTC),
                    request -> submissions.getAndIncrement() == 0
                            ? RemoteDispatchResult.unavailable()
                            : new RemoteDispatchResult(1, 1, List.of("orders-1")),
                    executions
            );
            JobDefinition definition = JobDefinition.builder()
                    .id("retryable-transport-job")
                    .name("Retryable transport job")
                    .handlerName("remote:orders:refreshCache")
                    .schedule(new CronSchedule("0 * * * * *"))
                    .build();
            ExecutionCommand command = new ExecutionCommand(
                    "retryable-transport-exec", definition, now, now, "scheduler-a", 7L
            );

            assertFalse(dispatcher.submit(command).accepted());
            assertEquals(ExecutionStatus.DISPATCHING,
                    executions.findExecution(command.executionId()).orElseThrow().status());
            assertTrue(dispatcher.submit(command).accepted());
            assertEquals(ExecutionStatus.DISPATCHING,
                    executions.findExecution(command.executionId()).orElseThrow().status());
        } finally {
            workers.shutdownNow();
        }
    }
}
