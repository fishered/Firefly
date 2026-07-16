package com.firefly.server;

import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.engine.JobDispatcher;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.store.DispatchType;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutboxWorkerTest {
    @Test
    void localOutboxStaysSentUntilTheHandlerCompletes() throws Exception {
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryJobRepository repository = new InMemoryJobRepository();
        InMemoryJobHandlerRegistry handlers = new InMemoryJobHandlerRegistry();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        handlers.register("local-handler", ignored -> {
            started.countDown();
            release.await();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        JobDispatcher dispatcher = new JobDispatcher(handlers, executor, clock);
        JobDefinition job = JobDefinition.builder()
                .id("local-job").name("Local job").handlerName("local-handler")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .timeout(Duration.ofSeconds(30)).build();
        assertTrue(repository.enqueueManual(new ExecutionCommand("local-exec", job, now, now)));
        DispatchOutboxWorker worker = new DispatchOutboxWorker(
                "scheduler-a", repository, dispatcher, clock, Set.of(DispatchType.LOCAL)
        );

        worker.drain();
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1L, repository.outboxCounts().get(DispatchOutboxStatus.SENT));

        release.countDown();
        await(() -> repository.outboxCounts().getOrDefault(DispatchOutboxStatus.DONE, 0L) == 1L);
        executor.shutdownNow();
        worker.close();
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
