package com.firefly.server;

import com.firefly.metrics.SchedulerMetrics;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Virtual-thread executor with non-blocking admission and bounded shutdown. */
final class BoundedVirtualThreadExecutor extends AbstractExecutorService {
    private final ExecutorService delegate;
    private final Semaphore permits;
    private final int maxConcurrency;
    private final Duration shutdownTimeout;
    private final SchedulerMetrics metrics;

    BoundedVirtualThreadExecutor(LocalWorkerOptions options, SchedulerMetrics metrics) {
        Objects.requireNonNull(options, "options");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.maxConcurrency = options.maxConcurrency();
        this.shutdownTimeout = options.shutdownTimeout();
        this.permits = new Semaphore(maxConcurrency);
        this.delegate = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("firefly-worker-", 0).factory()
        );
        metrics.localWorkerCapacity(maxConcurrency);
        metrics.localWorkerActive(0);
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (isShutdown() || !permits.tryAcquire()) {
            metrics.recordLocalWorkerRejection();
            throw new RejectedExecutionException("local worker concurrency limit reached: " + maxConcurrency);
        }
        metrics.localWorkerActive(maxConcurrency - permits.availablePermits());
        try {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    permits.release();
                    metrics.localWorkerActive(maxConcurrency - permits.availablePermits());
                }
            });
        } catch (RuntimeException failure) {
            permits.release();
            metrics.localWorkerActive(maxConcurrency - permits.availablePermits());
            throw failure;
        }
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        delegate.shutdown();
        try {
            if (!delegate.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                delegate.shutdownNow();
                delegate.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            delegate.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
