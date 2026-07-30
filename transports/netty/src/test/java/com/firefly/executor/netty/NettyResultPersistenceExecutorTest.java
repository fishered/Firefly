package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyResultPersistenceExecutorTest {
    @Test
    void retriesSaturatedTasksWithoutBlockingTheSubmittingThread() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch retriedTaskRan = new CountDownLatch(1);
        AtomicBoolean exhausted = new AtomicBoolean();

        try (NettyResultPersistenceExecutor executor = new NettyResultPersistenceExecutor(
                1, 1, 50, Duration.ofMillis(5)
        )) {
            executor.execute(() -> await(workerStarted, releaseWorker));
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
            executor.execute(() -> { });

            NettyResultPersistenceExecutor.Submission submission = executor.submit(
                    retriedTaskRan::countDown, () -> exhausted.set(true)
            );

            assertEquals(NettyResultPersistenceExecutor.Submission.RETRYING, submission);
            assertFalse(exhausted.get());
            assertEquals(1, executor.pendingRetries());
            releaseWorker.countDown();
            assertTrue(retriedTaskRan.await(2, TimeUnit.SECONDS));
            assertFalse(exhausted.get());
        } finally {
            releaseWorker.countDown();
        }
    }

    @Test
    void boundsPendingRetriesAndSignalsExhaustion() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch exhausted = new CountDownLatch(1);

        try (NettyResultPersistenceExecutor executor = new NettyResultPersistenceExecutor(
                1, 1, 2, Duration.ofMillis(5)
        )) {
            executor.execute(() -> await(workerStarted, releaseWorker));
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
            executor.execute(() -> { });

            assertEquals(NettyResultPersistenceExecutor.Submission.RETRYING,
                    executor.submit(() -> { }, exhausted::countDown));
            assertEquals(NettyResultPersistenceExecutor.Submission.REJECTED,
                    executor.submit(() -> { }, () -> { }));
            assertTrue(exhausted.await(1, TimeUnit.SECONDS));
            assertEquals(0, executor.pendingRetries());
            releaseWorker.countDown();
        } finally {
            releaseWorker.countDown();
        }
    }

    private void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
