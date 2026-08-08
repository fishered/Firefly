package com.firefly.server;

import com.firefly.metrics.SchedulerMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedVirtualThreadExecutorTest {
    @Test
    void rejectsImmediatelyWhenConcurrencyLimitIsFull() throws Exception {
        SchedulerMetrics metrics = new SchedulerMetrics();
        BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(
                new LocalWorkerOptions(1, Duration.ofSeconds(1)), metrics
        );
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                running.countDown();
                release.await();
                return null;
            });
            assertTrue(running.await(1, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> { }));
            assertEquals(1, metrics.snapshot().localWorkerActive());
            assertEquals(1, metrics.snapshot().localWorkerMaxConcurrency());
            assertEquals(1, metrics.snapshot().localWorkerRejections());
        } finally {
            release.countDown();
            executor.close();
        }
    }
}
