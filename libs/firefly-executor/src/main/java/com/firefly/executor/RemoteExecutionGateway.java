package com.firefly.executor;

/** Transport-neutral remote execution boundary used by the scheduler runtime. */
@FunctionalInterface
public interface RemoteExecutionGateway {
    RemoteDispatchResult dispatch(RemoteDispatchRequest request);
}
