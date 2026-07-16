package com.firefly.server;

import com.firefly.executor.RemoteDispatchRequest;
import com.firefly.executor.RemoteDispatchResult;
import com.firefly.executor.RemoteExecutionGateway;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Allows bootstrap to install a transport after dependency injection has assembled the scheduler. */
public final class SwitchableRemoteExecutionGateway implements RemoteExecutionGateway {
    private final AtomicReference<RemoteExecutionGateway> delegate = new AtomicReference<>();

    public void install(RemoteExecutionGateway gateway) {
        delegate.set(Objects.requireNonNull(gateway, "gateway"));
    }

    public void clear() {
        delegate.set(null);
    }

    @Override
    public RemoteDispatchResult dispatch(RemoteDispatchRequest request) {
        RemoteExecutionGateway current = delegate.get();
        return current == null ? RemoteDispatchResult.unavailable() : current.dispatch(request);
    }
}
