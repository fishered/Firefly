package com.firefly.plugin;

/** Optional transport bridge used by operational plugins to request cooperative cancellation. */
@FunctionalInterface
public interface ExecutionCancellationDispatcher {
    int cancel(String executionId, String reason);
}
