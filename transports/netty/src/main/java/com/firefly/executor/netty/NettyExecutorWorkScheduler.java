package com.firefly.executor.netty;

import com.firefly.metrics.SchedulerMetrics;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class NettyExecutorWorkScheduler implements AutoCloseable {
    private final ExecutorService workerPool;
    private final boolean ownsWorkerPool;
    private final NettyExecutorResourceOptions options;
    private final SchedulerMetrics metrics;
    private final Semaphore acceptedSlots;
    private final Semaphore runningSlots;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong acceptedCpuMillis = new AtomicLong();
    private final AtomicLong acceptedMemoryBytes = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicInteger> tenantAccepted = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private NettyExecutorWorkScheduler(
            ExecutorService workerPool,
            boolean ownsWorkerPool,
            NettyExecutorResourceOptions options,
            SchedulerMetrics metrics
    ) {
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.ownsWorkerPool = ownsWorkerPool;
        this.options = Objects.requireNonNull(options, "options");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.acceptedSlots = new Semaphore(options.maxConcurrentExecutions() + options.queueCapacity());
        this.runningSlots = new Semaphore(options.maxConcurrentExecutions());
        this.metrics.executorClientCapacity(options.maxConcurrentExecutions(), options.queueCapacity());
        updateMetrics();
    }

    static NettyExecutorWorkScheduler owned(NettyExecutorResourceOptions options, SchedulerMetrics metrics) {
        NettyExecutorResourceOptions actual = options == null ? NettyExecutorResourceOptions.defaults() : options;
        if (actual.maxConcurrentExecutions() > actual.workerThreads()) {
            throw new IllegalArgumentException("maxConcurrentExecutions must not exceed workerThreads for owned pools");
        }
        return new NettyExecutorWorkScheduler(
                new ThreadPoolExecutor(
                        actual.workerThreads(),
                        actual.workerThreads(),
                        0L,
                        TimeUnit.MILLISECONDS,
                        actual.queueCapacity() == 0
                                ? new SynchronousQueue<>()
                                : new ArrayBlockingQueue<>(actual.queueCapacity()),
                        threadFactory(),
                        new ThreadPoolExecutor.AbortPolicy()
                ),
                true,
                actual,
                metrics
        );
    }

    static NettyExecutorWorkScheduler borrowed(
            ExecutorService workerPool,
            NettyExecutorResourceOptions options,
            SchedulerMetrics metrics
    ) {
        return new NettyExecutorWorkScheduler(
                workerPool,
                false,
                options == null ? NettyExecutorResourceOptions.defaults() : options,
                metrics
        );
    }

    Future<?> submit(Runnable command) {
        return submit("default", 0, 0, command);
    }

    Future<?> submit(String tenantId, Runnable command) {
        return submit(tenantId, 0, 0, command);
    }

    Future<?> submit(String tenantId, long cpuMillis, long memoryBytes, Runnable command) {
        Objects.requireNonNull(command, "command");
        if (cpuMillis < 0 || memoryBytes < 0) {
            throw new IllegalArgumentException("resource requirements must not be negative");
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        if (closed.get() || !acceptedSlots.tryAcquire()) {
            throw new RejectedExecutionException("executor overloaded");
        }
        accepted.incrementAndGet();
        acceptedCpuMillis.addAndGet(cpuMillis);
        acceptedMemoryBytes.addAndGet(memoryBytes);
        tenantAccepted.computeIfAbsent(tenant, ignored -> new AtomicInteger()).incrementAndGet();
        updateMetrics();
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        FutureTask<Void> task = new FutureTask<>(() -> {
            started.set(true);
            boolean runningAcquired = false;
            try {
                runningSlots.acquire();
                runningAcquired = true;
                running.incrementAndGet();
                updateMetrics();
                command.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (runningAcquired) {
                    running.decrementAndGet();
                    runningSlots.release();
                }
                releaseAccepted(released, tenant, cpuMillis, memoryBytes);
            }
            return null;
        }) {
            @Override
            protected void done() {
                if (isCancelled() && !started.get()) {
                    releaseAccepted(released, tenant, cpuMillis, memoryBytes);
                }
            }
        };
        try {
            workerPool.execute(task);
            return task;
        } catch (RuntimeException rejected) {
            releaseAccepted(released, tenant, cpuMillis, memoryBytes);
            throw rejected;
        }
    }

    void recordOverloadAck() {
        metrics.recordExecutorOverloadAck();
    }

    int runningExecutions() {
        return running.get();
    }

    int queuedExecutions() {
        return Math.max(0, accepted.get() - running.get());
    }

    int acceptedExecutions() {
        return accepted.get();
    }

    long acceptedCpuMillis() {
        return acceptedCpuMillis.get();
    }

    long acceptedMemoryBytes() {
        return acceptedMemoryBytes.get();
    }

    Map<String, Integer> tenantWorkloads() {
        return tenantAccepted.entrySet().stream()
                .filter(entry -> entry.getValue().get() > 0)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().get()
                ));
    }

    NettyExecutorResourceOptions options() {
        return options;
    }

    boolean ownsWorkerPool() {
        return ownsWorkerPool;
    }

    @Override
    public void close() {
        closed.set(true);
        if (ownsWorkerPool) {
            workerPool.shutdownNow().forEach(task -> {
                if (task instanceof Future<?> future) {
                    future.cancel(false);
                }
            });
        }
        updateMetrics();
    }

    private void releaseAccepted(
            AtomicBoolean released, String tenantId, long cpuMillis, long memoryBytes
    ) {
        if (released.compareAndSet(false, true)) {
            accepted.decrementAndGet();
            acceptedCpuMillis.addAndGet(-cpuMillis);
            acceptedMemoryBytes.addAndGet(-memoryBytes);
            tenantAccepted.computeIfPresent(tenantId, (ignored, value) ->
                    value.decrementAndGet() <= 0 ? null : value
            );
            acceptedSlots.release();
            updateMetrics();
        }
    }

    private void updateMetrics() {
        metrics.executorClientWorkload(runningExecutions(), queuedExecutions());
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "firefly-executor-worker-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
