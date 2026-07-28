package com.firefly.executor.netty;

/**
 * Bounded resource controls for a business-side Netty executor client.
 */
public record NettyExecutorResourceOptions(
        int workerThreads,
        int queueCapacity,
        int maxConcurrentExecutions
) {
    public NettyExecutorResourceOptions {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must not be negative");
        }
        if (maxConcurrentExecutions < 1) {
            throw new IllegalArgumentException("maxConcurrentExecutions must be positive");
        }
    }

    public static NettyExecutorResourceOptions defaults() {
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new NettyExecutorResourceOptions(workers, 1024, workers);
    }
}
