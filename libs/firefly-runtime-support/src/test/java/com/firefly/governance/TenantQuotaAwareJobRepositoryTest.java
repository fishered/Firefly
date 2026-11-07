package com.firefly.governance;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.store.JobRepository;
import com.firefly.store.SchedulingAdvance;
import org.junit.jupiter.api.Test;

import java.time.Clock;
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

class TenantQuotaAwareJobRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void concurrentProducersCannotExceedTheGlobalBacklogQuota() throws Exception {
        InMemoryTenantQuotaStore quotas = quotas(3);
        SchedulerMetrics metrics = new SchedulerMetrics();
        JobRepository repository = TenantQuotaAwareJobRepository.wrap(
                new InMemoryJobRepository(), quotas, CLOCK, metrics
        );
        JobDefinition definition = job("job", "blue");
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(20);
        try {
            for (int index = 0; index < 20; index++) {
                int value = index;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (repository.enqueueManual(command("execution-" + value, definition))) {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(3, accepted.get());
        assertEquals(3, quotas.usage("blue", NOW).backlog());
        assertEquals(17L, metrics.tenantAdmissionRejections().get(
                new SchedulerMetrics.TenantAdmissionMetricKey(
                        "blue", TenantAdmissionReason.BACKLOG_QUOTA_EXCEEDED
                )
        ));
    }

    @Test
    void batchAdmissionDoesNotAdvanceACursorWhoseTenantIsAtCapacity() {
        InMemoryTenantQuotaStore quotas = quotas(1);
        InMemoryJobRepository delegate = new InMemoryJobRepository();
        JobDefinition first = job("first", "blue");
        JobDefinition second = job("second", "blue");
        delegate.save(first, NOW);
        delegate.save(second, NOW);
        JobRepository repository = TenantQuotaAwareJobRepository.wrap(
                delegate, quotas, CLOCK, new SchedulerMetrics()
        );
        List<SchedulingAdvance> advances = List.of(
                new SchedulingAdvance("first", NOW, NOW.plusSeconds(60),
                        List.of(command("first-run", first))),
                new SchedulingAdvance("second", NOW, NOW.plusSeconds(60),
                        List.of(command("second-run", second)))
        );

        assertEquals(List.of(true, false), repository.advanceAndEnqueueBatch(advances));
        assertEquals(NOW.plusSeconds(60), repository.find("first").orElseThrow().nextFireTime());
        assertEquals(NOW, repository.find("second").orElseThrow().nextFireTime());
        assertEquals(1, quotas.usage("blue", NOW).backlog());
    }

    @Test
    void duplicateEnqueueDoesNotReleaseTheExistingBacklogReservation() {
        InMemoryTenantQuotaStore quotas = quotas(1);
        JobRepository repository = TenantQuotaAwareJobRepository.wrap(
                new InMemoryJobRepository(), quotas, CLOCK, new SchedulerMetrics()
        );
        ExecutionCommand command = command("same", job("job", "blue"));

        assertTrue(repository.enqueueManual(command));
        assertFalse(repository.enqueueManual(command));
        assertEquals(1, quotas.usage("blue", NOW).backlog());
        assertEquals(TenantAdmissionReason.BACKLOG_QUOTA_EXCEEDED,
                repository.enqueueManualWithAdmission(
                        command("different", job("job", "blue"))
                ).reason());
    }

    private static InMemoryTenantQuotaStore quotas(long backlog) {
        InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore();
        store.save(new TenantDefinition(
                "blue", true, 1, 0, 0, 0, backlog, 0, 0, NOW
        ), 0, NOW);
        return store;
    }

    private static JobDefinition job(String id, String tenant) {
        return JobDefinition.builder().id(id).name(id).handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *"))
                .parameters(Map.of("firefly.resource.tenantId", tenant))
                .build();
    }

    private static ExecutionCommand command(String id, JobDefinition definition) {
        return new ExecutionCommand(id, definition, NOW, NOW);
    }
}
