package com.firefly.server;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.engine.JobDispatcher;
import com.firefly.executor.RemoteDispatchResult;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.store.DispatchType;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

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
        assertEquals(1L, repository.countActiveDispatchesOwnedBy("scheduler-a"));

        release.countDown();
        await(() -> repository.outboxCounts().getOrDefault(DispatchOutboxStatus.DONE, 0L) == 1L);
        assertEquals(0L, repository.countActiveDispatchesOwnedBy("scheduler-a"));
        executor.shutdownNow();
        worker.close();
    }

    @Test
    void acknowledgementTimeoutStopsAfterTheConfiguredDeliveryAttempts() {
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        InMemoryJobRepository repository = new InMemoryJobRepository(clock);
        AtomicInteger deliveries = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        JobDispatcher dispatcher = new JobDispatcher(
                new InMemoryJobHandlerRegistry(), executor, clock,
                request -> {
                    deliveries.incrementAndGet();
                    return new RemoteDispatchResult(1, 1, java.util.List.of("orders-1"));
                }
        );
        JobDefinition job = JobDefinition.builder()
                .id("missing-ack-job").name("Missing ACK job")
                .handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *"))
                .build();
        assertTrue(repository.enqueueManual(new ExecutionCommand("missing-ack-exec", job, now, now)));
        DispatchOutboxOptions options = new DispatchOutboxOptions(
                Duration.ofMillis(100), 10, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 2, Duration.ofSeconds(5)
        );
        com.firefly.metrics.SchedulerMetrics metrics = new com.firefly.metrics.SchedulerMetrics();
        DispatchOutboxWorker worker = new DispatchOutboxWorker(
                "gateway-a", repository, dispatcher, clock, Set.of(DispatchType.REMOTE),
                metrics, options
        );

        worker.drain();
        clock.set(now.plusSeconds(2));
        worker.drain();
        clock.set(now.plusSeconds(4));
        worker.drain();

        assertEquals(2, deliveries.get());
        assertEquals(1L, repository.outboxCounts().get(DispatchOutboxStatus.DEAD));
        assertEquals(1L, metrics.snapshot().outboxDeliveryExhaustions());
        worker.close();
        executor.shutdownNow();
    }

    @Test
    void gatewayWithoutALocalRouteDefersWithoutConsumingDeliveryAttempt() {
        Instant now = Instant.parse("2026-07-18T11:00:00Z");
        MutableClock clock = new MutableClock(now);
        InMemoryJobRepository repository = new InMemoryJobRepository(clock);
        AtomicInteger deliveries = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        JobDispatcher dispatcher = new JobDispatcher(
                new InMemoryJobHandlerRegistry(), executor, clock,
                request -> {
                    deliveries.incrementAndGet();
                    return new RemoteDispatchResult(1, 1, java.util.List.of("orders-1"));
                }
        );
        JobDefinition job = JobDefinition.builder()
                .id("route-job").name("Route job").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
        assertTrue(repository.enqueueManual(new ExecutionCommand("route-exec", job, now, now)));
        DispatchOutboxOptions options = new DispatchOutboxOptions(
                Duration.ofMillis(100), 10, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 2, Duration.ofSeconds(5)
        );
        DispatchOutboxWorker worker = new DispatchOutboxWorker(
                "gateway-without-route", repository, dispatcher, clock, Set.of(DispatchType.REMOTE),
                new com.firefly.metrics.SchedulerMetrics(), options, ignored -> false
        );

        worker.drain();
        assertEquals(0, deliveries.get());
        assertEquals(1L, repository.outboxCounts().get(DispatchOutboxStatus.RETRY));
        clock.set(now.plusMillis(100));
        var reclaimed = repository.claimDispatches(
                "gateway-with-route", clock.instant(), 1, Duration.ofSeconds(1), Set.of(DispatchType.REMOTE)
        );
        assertEquals(1, reclaimed.getFirst().attempt());
        worker.close();
        executor.shutdownNow();
    }

    @Test
    void drainingNodeDoesNotClaimNewOutboxRecords() {
        Instant now = Instant.parse("2026-07-20T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryJobRepository repository = new InMemoryJobRepository(clock);
        JobDefinition job = JobDefinition.builder()
                .id("drain-job").name("Drain job").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
        assertTrue(repository.enqueueManual(new ExecutionCommand("drain-exec", job, now, now)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DispatchOutboxWorker worker = new DispatchOutboxWorker(
                "gateway-a", repository,
                new JobDispatcher(new InMemoryJobHandlerRegistry(), executor, clock,
                        request -> new RemoteDispatchResult(1, 1, java.util.List.of("orders-1"))),
                clock, Set.of(DispatchType.REMOTE)
        );
        worker.setClaimAdmission(() -> false);

        worker.drain();

        assertEquals(1L, repository.outboxCounts().get(DispatchOutboxStatus.PENDING));
        assertEquals(0L, repository.countActiveDispatchesOwnedBy("gateway-a"));
        worker.close();
        executor.shutdownNow();
    }

    @Test
    void tenantQuotaDefersExcessWorkWithReasonAndReleasesAfterLocalCompletion() throws Exception {
        Instant now = Instant.parse("2026-10-20T10:00:00Z");
        MutableClock clock = new MutableClock(now);
        InMemoryJobRepository repository = new InMemoryJobRepository(clock);
        com.firefly.governance.InMemoryTenantQuotaStore quotas =
                new com.firefly.governance.InMemoryTenantQuotaStore();
        quotas.save(new com.firefly.governance.TenantDefinition(
                "blue", true, 1, 1, 100, 1_024, 10, 10, 0, now
        ), 0, now);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        InMemoryJobHandlerRegistry handlers = new InMemoryJobHandlerRegistry();
        handlers.register("limited", ignored -> {
            if (calls.getAndIncrement() == 0) {
                firstStarted.countDown();
                releaseFirst.await();
            }
        });
        JobDefinition job = JobDefinition.builder()
                .id("limited-job").name("Limited").handlerName("limited")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .parameters(Map.of(
                        "firefly.resource.tenantId", "blue",
                        "firefly.resource.cpuMillis", "100",
                        "firefly.resource.memoryBytes", "1024"
                )).build();
        assertTrue(repository.enqueueManual(new ExecutionCommand("limited-1", job, now, now)));
        assertTrue(repository.enqueueManual(new ExecutionCommand("limited-2", job, now, now)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        com.firefly.metrics.SchedulerMetrics metrics = new com.firefly.metrics.SchedulerMetrics();
        DispatchOutboxWorker worker = new DispatchOutboxWorker(
                "scheduler-a", repository, new JobDispatcher(handlers, executor, clock), clock,
                Set.of(DispatchType.LOCAL), metrics,
                new DispatchOutboxOptions(Duration.ofMillis(100), 2, Duration.ofSeconds(1),
                        Duration.ofSeconds(1), 3, Duration.ofSeconds(5))
        );
        worker.configureTenantFairness(quotas, Duration.ofSeconds(1));

        worker.drain();
        assertTrue(firstStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, quotas.usage("blue", now).activeExecutions());
        assertEquals(1L, repository.outboxCounts().get(DispatchOutboxStatus.SENT));
        assertEquals(1L, repository.outboxCounts().get(DispatchOutboxStatus.RETRY));
        assertTrue(java.util.stream.Stream.of("limited-1", "limited-2")
                .map(id -> repository.findDispatch(id).orElseThrow())
                .anyMatch(record -> record.lastError().contains("CONCURRENCY_QUOTA_EXCEEDED")));
        assertEquals(1L, metrics.tenantAdmissionRejections().get(
                new com.firefly.metrics.SchedulerMetrics.TenantAdmissionMetricKey(
                        "blue", com.firefly.governance.TenantAdmissionReason.CONCURRENCY_QUOTA_EXCEEDED
                )));

        releaseFirst.countDown();
        await(() -> quotas.usage("blue", clock.instant()).activeExecutions() == 0);
        clock.set(now.plusMillis(100));
        worker.drain();
        await(() -> repository.outboxCounts().getOrDefault(DispatchOutboxStatus.DONE, 0L) == 2L);
        assertEquals(2, calls.get());
        assertEquals(0, quotas.usage("blue", clock.instant()).activeExecutions());
        worker.close();
        executor.shutdownNow();
    }

    @Test
    @org.junit.jupiter.api.Tag("production-readiness")
    void staleLocalCompletionCannotReleaseTakeoverReservation() throws Exception {
        Instant now = Instant.parse("2026-10-20T11:00:00Z");
        MutableClock clock = new MutableClock(now);
        InMemoryJobRepository repository = new InMemoryJobRepository(clock);
        com.firefly.governance.InMemoryTenantQuotaStore quotas =
                new com.firefly.governance.InMemoryTenantQuotaStore();
        quotas.save(new com.firefly.governance.TenantDefinition(
                "blue", true, 1, 1, 0, 0, 0, 0, 0, now
        ), 0, now);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        InMemoryJobHandlerRegistry handlers = new InMemoryJobHandlerRegistry();
        handlers.register("takeover", ignored -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                firstStarted.countDown();
                releaseFirst.await();
            } else {
                secondStarted.countDown();
                releaseSecond.await();
            }
        });
        JobDefinition job = JobDefinition.builder()
                .id("takeover-job").name("Takeover").handlerName("takeover")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .concurrencyPolicy(com.firefly.domain.ConcurrencyPolicy.ALLOW)
                .timeout(Duration.ofSeconds(30))
                .parameters(Map.of("firefly.resource.tenantId", "blue"))
                .build();
        assertTrue(repository.enqueueManual(new ExecutionCommand("takeover-exec", job, now, now)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        JobDispatcher dispatcher = new JobDispatcher(handlers, executor, clock);
        DispatchOutboxOptions options = new DispatchOutboxOptions(
                Duration.ofMillis(100), 1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), 3, Duration.ofSeconds(5)
        );
        DispatchOutboxWorker first = new DispatchOutboxWorker(
                "scheduler-a", repository, dispatcher, clock, Set.of(DispatchType.LOCAL),
                new com.firefly.metrics.SchedulerMetrics(), options
        );
        DispatchOutboxWorker takeover = new DispatchOutboxWorker(
                "scheduler-b", repository, dispatcher, clock, Set.of(DispatchType.LOCAL),
                new com.firefly.metrics.SchedulerMetrics(), options
        );
        first.configureTenantFairness(quotas, Duration.ofSeconds(1));
        takeover.configureTenantFairness(quotas, Duration.ofSeconds(1));

        first.drain();
        assertTrue(firstStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, quotas.usage("blue", now).activeExecutions());
        clock.set(now.plusSeconds(32));
        takeover.drain();
        assertTrue(secondStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, quotas.usage("blue", clock.instant()).activeExecutions());

        releaseFirst.countDown();
        Thread.sleep(50);
        assertEquals(1, quotas.usage("blue", clock.instant()).activeExecutions());
        assertEquals(DispatchOutboxStatus.SENT,
                repository.findDispatch("takeover-exec").orElseThrow().status());

        releaseSecond.countDown();
        await(() -> quotas.usage("blue", clock.instant()).activeExecutions() == 0);
        assertEquals(DispatchOutboxStatus.DONE,
                repository.findDispatch("takeover-exec").orElseThrow().status());
        first.close();
        takeover.close();
        executor.shutdownNow();
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
