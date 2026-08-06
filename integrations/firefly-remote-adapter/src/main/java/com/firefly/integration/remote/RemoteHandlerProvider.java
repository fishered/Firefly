package com.firefly.integration.remote;

/** Declares the fixed handler names exposed by one business service. */
@FunctionalInterface
public interface RemoteHandlerProvider {
    void register(RemoteHandlerRegistry registry);
}
