package com.firefly.plugin;

import com.firefly.executor.ExecutorIsolationResult;

@FunctionalInterface
public interface ExecutorIsolationDispatcher {
    ExecutorIsolationResult isolate(String executorName);
}
