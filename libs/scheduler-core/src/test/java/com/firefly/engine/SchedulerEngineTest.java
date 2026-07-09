package com.firefly.engine;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.ExecutionContext;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.MisfirePolicy;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerEngineTest {
    @Test
    void firesDueJobAndAdvancesNextFireTime() {
        Instant now = Instant.parse("2026-07-06T00:00:10Z");
        TestFixture fixture = new TestFixture(now);
        JobDefinition job = jobBuilder("job-a")
                .misfireGrace(Duration.ofSeconds(20))
                .build();
        fixture.repository.save(job, Instant.parse("2026-07-06T00:00:10Z"));

        fixture.engine.tick();

        assertEquals(List.of(Instant.parse("2026-07-06T00:00:10Z")), fixture.scheduledFireTimes());
        assertEquals(Instant.parse("2026-07-06T00:00:15Z"),
                fixture.repository.find("job-a").orElseThrow().nextFireTime());
    }

    @Test
    void skipsMisfiredJobWhenPolicyIsSkip() {
        Instant now = Instant.parse("2026-07-06T00:00:20Z");
        TestFixture fixture = new TestFixture(now);
        JobDefinition job = jobBuilder("job-a")
                .misfirePolicy(MisfirePolicy.SKIP)
                .misfireGrace(Duration.ZERO)
                .build();
        fixture.repository.save(job, Instant.parse("2026-07-06T00:00:00Z"));

        fixture.engine.tick();

        assertTrue(fixture.scheduledFireTimes().isEmpty());
        assertEquals(Instant.parse("2026-07-06T00:00:25Z"),
                fixture.repository.find("job-a").orElseThrow().nextFireTime());
    }

    @Test
    void firesOnceWhenPolicyIsFireOnce() {
        Instant now = Instant.parse("2026-07-06T00:00:20Z");
        TestFixture fixture = new TestFixture(now);
        JobDefinition job = jobBuilder("job-a")
                .misfirePolicy(MisfirePolicy.FIRE_ONCE)
                .misfireGrace(Duration.ZERO)
                .build();
        fixture.repository.save(job, Instant.parse("2026-07-06T00:00:00Z"));

        fixture.engine.tick();

        assertEquals(List.of(Instant.parse("2026-07-06T00:00:00Z")), fixture.scheduledFireTimes());
        assertEquals(Instant.parse("2026-07-06T00:00:25Z"),
                fixture.repository.find("job-a").orElseThrow().nextFireTime());
    }

    @Test
    void catchesUpUntilMaxCatchUpCount() {
        Instant now = Instant.parse("2026-07-06T00:00:20Z");
        TestFixture fixture = new TestFixture(now);
        JobDefinition job = jobBuilder("job-a")
                .misfirePolicy(MisfirePolicy.CATCH_UP)
                .misfireGrace(Duration.ZERO)
                .maxCatchUpCount(3)
                .build();
        fixture.repository.save(job, Instant.parse("2026-07-06T00:00:00Z"));

        fixture.engine.tick();

        assertEquals(List.of(
                Instant.parse("2026-07-06T00:00:00Z"),
                Instant.parse("2026-07-06T00:00:05Z"),
                Instant.parse("2026-07-06T00:00:10Z")
        ), fixture.scheduledFireTimes());
        assertEquals(Instant.parse("2026-07-06T00:00:15Z"),
                fixture.repository.find("job-a").orElseThrow().nextFireTime());
    }

    @Test
    void drainsMultipleDueBatchesInSingleTick() {
        Instant now = Instant.parse("2026-07-06T00:00:10Z");
        TestFixture fixture = new TestFixture(now);

        for (int i = 0; i < 150; i++) {
            JobDefinition job = jobBuilder("job-" + i)
                    .misfireGrace(Duration.ofSeconds(20))
                    .build();
            fixture.repository.save(job, now);
        }

        fixture.engine.tick();

        assertEquals(150, fixture.scheduledFireTimes().size());
        assertTrue(fixture.scheduledFireTimes().stream().allMatch(now::equals));
        assertEquals(150, fixture.dispatchTimes().size());
    }

    @Test
    void drainsManyDifferentFireTimesInSingleTick() {
        Instant now = Instant.parse("2026-07-06T00:10:00Z");
        TestFixture fixture = new TestFixture(now);

        for (int i = 0; i < 150; i++) {
            JobDefinition job = jobBuilder("job-" + i)
                    .misfireGrace(Duration.ofMinutes(30))
                    .build();
            fixture.repository.save(job, now.minusSeconds(i + 1L));
        }

        fixture.engine.tick();

        assertEquals(150, fixture.scheduledFireTimes().size());
    }

    private static JobDefinition.Builder jobBuilder(String id) {
        return JobDefinition.builder()
                .id(id)
                .name(id)
                .handlerName("handler")
                .schedule(new FixedRateSchedule(Duration.ofSeconds(5)))
                .concurrencyPolicy(ConcurrencyPolicy.ALLOW)
                .timeout(Duration.ofSeconds(10));
    }

    private static final class TestFixture {
        private final InMemoryJobRepository repository = new InMemoryJobRepository();
        private final InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        private final List<ExecutionContext> executions = new ArrayList<>();
        private final SchedulerEngine engine;

        private TestFixture(Instant now) {
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            ExecutorService directExecutor = new DirectExecutorService();
            registry.register("handler", executions::add);
            JobDispatcher dispatcher = new JobDispatcher(registry, directExecutor, clock);
            engine = new SchedulerEngine(repository, dispatcher, clock);
        }

        private List<Instant> scheduledFireTimes() {
            return executions.stream()
                    .map(ExecutionContext::scheduledFireTime)
                    .toList();
        }

        private List<Instant> dispatchTimes() {
            return executions.stream()
                    .map(ExecutionContext::dispatchTime)
                    .toList();
        }
    }

    private static final class DirectExecutorService implements ExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
            try {
                return java.util.concurrent.CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                java.util.concurrent.CompletableFuture<T> failed = new java.util.concurrent.CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            task.run();
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        }

        @Override
        public Future<?> submit(Runnable task) {
            task.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            return tasks.stream().map(this::submit).toList();
        }

        @Override
        public <T> List<Future<T>> invokeAll(
                Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout,
                TimeUnit unit
        ) {
            return invokeAll(tasks);
        }

        @Override
        public <T> T invokeAny(Collection<? extends java.util.concurrent.Callable<T>> tasks) throws ExecutionException {
            if (tasks.isEmpty()) {
                throw new IllegalArgumentException("tasks must not be empty");
            }
            try {
                return tasks.iterator().next().call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }

        @Override
        public <T> T invokeAny(
                Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout,
                TimeUnit unit
        ) throws ExecutionException {
            return invokeAny(tasks);
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}

