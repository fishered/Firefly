package com.firefly.executor.netty;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes result mutations with bounded, delayed retries outside Netty event loops. */
final class NettyResultPersistenceExecutor implements java.util.concurrent.Executor, AutoCloseable {
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(10);
    static final int DEFAULT_MAX_RETRY_ATTEMPTS = 100;

    enum Submission {
        ACCEPTED,
        RETRYING,
        REJECTED
    }

    private final ThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor retryScheduler;
    private final Semaphore retrySlots;
    private final int retryCapacity;
    private final int maxRetryAttempts;
    private final long retryDelayMillis;
    private final AtomicBoolean closed = new AtomicBoolean();

    NettyResultPersistenceExecutor(int queueCapacity) {
        this(queueCapacity, queueCapacity, DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_RETRY_DELAY);
    }

    NettyResultPersistenceExecutor(
            int queueCapacity,
            int retryCapacity,
            int maxRetryAttempts,
            Duration retryDelay
    ) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        if (retryCapacity < 1) throw new IllegalArgumentException("retryCapacity must be positive");
        if (maxRetryAttempts < 1) throw new IllegalArgumentException("maxRetryAttempts must be positive");
        Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
        this.retryCapacity = retryCapacity;
        this.retrySlots = new Semaphore(retryCapacity);
        this.maxRetryAttempts = maxRetryAttempts;
        this.retryDelayMillis = Math.max(1, retryDelay.toMillis());
        this.worker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                task -> newThread(task, "firefly-execution-results"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.retryScheduler = new ScheduledThreadPoolExecutor(
                1, task -> newThread(task, "firefly-execution-result-retry")
        );
        this.retryScheduler.setRemoveOnCancelPolicy(true);
        this.retryScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    Submission submit(Runnable task, Runnable onExhausted) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(onExhausted, "onExhausted");
        if (closed.get()) return Submission.REJECTED;
        try {
            worker.execute(task);
            return Submission.ACCEPTED;
        } catch (RejectedExecutionException saturated) {
            if (worker.isShutdown() || !retrySlots.tryAcquire()) return Submission.REJECTED;
            RetryTask retryTask = new RetryTask(task, onExhausted);
            if (!schedule(retryTask)) {
                retrySlots.release();
                return Submission.REJECTED;
            }
            return Submission.RETRYING;
        }
    }

    @Override
    public void execute(Runnable command) {
        Submission submission = submit(command, () -> { });
        if (submission == Submission.REJECTED) {
            throw new RejectedExecutionException("result persistence retry queue is saturated or closed");
        }
    }

    boolean belowLowWatermark() {
        int queueCapacity = worker.getQueue().size() + worker.getQueue().remainingCapacity();
        return worker.getQueue().size() <= queueCapacity / 2 && pendingRetries() == 0;
    }

    int pendingRetries() {
        return retryCapacity - retrySlots.availablePermits();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        retryScheduler.shutdownNow();
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    private boolean schedule(RetryTask retryTask) {
        if (closed.get()) return false;
        try {
            retryScheduler.schedule(retryTask, retryDelayMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException closedScheduler) {
            return false;
        }
    }

    private static Thread newThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(false);
        return thread;
    }

    private final class RetryTask implements Runnable {
        private final Runnable delegate;
        private final Runnable onExhausted;
        private int attempts;

        private RetryTask(Runnable delegate, Runnable onExhausted) {
            this.delegate = delegate;
            this.onExhausted = onExhausted;
        }

        @Override
        public void run() {
            if (closed.get()) {
                retrySlots.release();
                return;
            }
            try {
                worker.execute(delegate);
                retrySlots.release();
            } catch (RejectedExecutionException saturated) {
                attempts++;
                if (worker.isShutdown() || attempts >= maxRetryAttempts || !schedule(this)) {
                    retrySlots.release();
                    onExhausted.run();
                }
            }
        }
    }
}
