package com.firefly.executor;

/**
 * Transport-neutral lifecycle and operational boundary for a remote executor gateway.
 * Implementations may use Netty, HTTP/2, gRPC, or another wire transport.
 */
public interface RemoteExecutorTransport extends RemoteExecutionGateway, AutoCloseable {
    void start() throws InterruptedException;

    ExecutorRegistry executorRegistry();

    boolean hasRoute(String executorName);

    int connectedExecutorCount();

    int disconnectAllExecutors();

    int cancel(String executionId, String reason);

    ExecutorIsolationResult isolateDetailed(String executorName);

    @Override
    void close();
}
