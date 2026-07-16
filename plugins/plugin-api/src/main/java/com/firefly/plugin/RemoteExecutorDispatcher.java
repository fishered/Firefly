package com.firefly.plugin;

import com.firefly.executor.RemoteExecutionGateway;

/**
 * Optional bridge used by plugins to dispatch scheduled work to a remote executor transport.
 */
@FunctionalInterface
public interface RemoteExecutorDispatcher extends RemoteExecutionGateway {
}
