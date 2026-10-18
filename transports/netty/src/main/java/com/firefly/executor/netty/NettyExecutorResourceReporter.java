package com.firefly.executor.netty;

import com.firefly.executor.ExecutorResourceReport;

/** Supplies the live capacity attached to executor registration and heartbeat frames. */
@FunctionalInterface
public interface NettyExecutorResourceReporter {
    ExecutorResourceReport report();
}
