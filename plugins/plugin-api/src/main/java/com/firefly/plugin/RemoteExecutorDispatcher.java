package com.firefly.plugin;

import com.firefly.domain.ExecutionContext;

/**
 * Optional bridge used by plugins to dispatch scheduled work to a remote executor transport.
 */
@FunctionalInterface
public interface RemoteExecutorDispatcher {
    boolean dispatch(String executorName, String handlerName, ExecutionContext context);
}
