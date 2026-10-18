package com.firefly.executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorResourceMetadataTest {
    @Tag("production-readiness")
    @Test
    void indexesTenThousandLiveExecutorLocations() {
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        InMemoryExecutorInstanceDirectory directory = new InMemoryExecutorInstanceDirectory();
        for (int index = 0; index < 10_000; index++) {
            directory.register(new ExecutorInstanceLocation(
                    "bulk", "instance-" + index, "gateway-" + index % 3,
                    "http://gateway-" + index % 3, "session-" + index,
                    now, now.plusSeconds(90), ExecutorResourceMetadata.merge(
                            Map.of(), new ExecutorResourceReport(1_000, 1_024, Set.of("bulk"), 0, Map.of())
                    )
            ));
        }

        assertEquals(10_000, directory.listOnline("bulk", now).size());
        ExecutorInstanceLocation target = directory.findOnlineInstance("instance-9999", now).orElseThrow();
        assertTrue(directory.tryReserve(target,
                new ExecutorResourceRequirement("tenant", 100, 128, Set.of("bulk"), 0, 10), now));
    }

    @Test
    void roundTripsTenantCountsAndAtomicallyLimitsTenantReservations() throws Exception {
        ExecutorResourceReport report = new ExecutorResourceReport(
                4000, 8192, Set.of("ssd", "zone-a"), 0, Map.of()
        );
        Map<String, String> metadata = ExecutorResourceMetadata.merge(Map.of("serviceName", "orders"), report);
        InMemoryExecutorInstanceDirectory directory = new InMemoryExecutorInstanceDirectory();
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        ExecutorInstanceLocation location = new ExecutorInstanceLocation(
                "orders", "instance-a", "gateway-a", "127.0.0.1:9700", "session-a",
                now, now.plus(Duration.ofMinutes(1)), metadata
        );
        directory.register(location);
        ExecutorResourceRequirement requirement = new ExecutorResourceRequirement(
                "tenant-a", 1000, 1024, Set.of("ssd"), 0, 1
        );

        int attempts = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(attempts)) {
            for (int index = 0; index < attempts; index++) {
                pool.submit(() -> {
                    start.await();
                    if (directory.tryReserve(location, requirement, now)) accepted.incrementAndGet();
                    return null;
                });
            }
            start.countDown();
        }

        assertEquals(1, accepted.get());
        ExecutorInstanceLocation updated = directory.listOnline("orders", now).getFirst();
        ExecutorResourceSnapshot snapshot = ExecutorResourceMetadata.snapshot(
                "orders", "instance-a", updated.metadata(), "tenant-a"
        ).orElseThrow();
        assertEquals(3000, snapshot.availableCpuMillis());
        assertEquals(7168, snapshot.availableMemoryBytes());
        assertEquals(1, snapshot.activeExecutions());
        assertEquals(1, snapshot.tenantActiveExecutions());
        assertTrue(snapshot.tags().containsAll(Set.of("ssd", "zone-a")));
    }
}
