package com.firefly.executor.netty;

import com.firefly.executor.ExecutorResourceReport;

import java.util.Set;

/** Default JVM and worker-pool resource sampler used when applications do not provide one. */
final class DefaultNettyExecutorResourceReporter implements NettyExecutorResourceReporter {
    private final NettyExecutorWorkScheduler workScheduler;
    private final long cpuCapacityMillis;
    private final long memoryCapacityBytes;
    private final Set<String> tags;

    DefaultNettyExecutorResourceReporter(
            NettyExecutorWorkScheduler workScheduler,
            long cpuCapacityMillis,
            long memoryCapacityBytes,
            Set<String> tags
    ) {
        this.workScheduler = java.util.Objects.requireNonNull(workScheduler, "workScheduler");
        this.cpuCapacityMillis = cpuCapacityMillis > 0
                ? cpuCapacityMillis : Runtime.getRuntime().availableProcessors() * 1000L;
        this.memoryCapacityBytes = memoryCapacityBytes > 0
                ? memoryCapacityBytes : Runtime.getRuntime().maxMemory();
        this.tags = Set.copyOf(tags == null ? Set.of() : tags);
    }

    @Override
    public ExecutorResourceReport report() {
        int active = workScheduler.acceptedExecutions();
        long usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long committedMemory = Math.max(usedHeap, workScheduler.acceptedMemoryBytes());
        long committedCpu = Math.max(active * 1000L, workScheduler.acceptedCpuMillis());
        long availableMemory = Math.max(0, memoryCapacityBytes - committedMemory);
        long availableCpu = Math.max(0, cpuCapacityMillis - committedCpu);
        return new ExecutorResourceReport(
                availableCpu, availableMemory, tags, active, workScheduler.tenantWorkloads()
        );
    }
}
