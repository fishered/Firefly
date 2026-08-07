package com.firefly.integration.remote;

/** Declares the fixed handler names exposed by one business service. */
@FunctionalInterface
public interface RemoteHandlerProvider {
    void register(RemoteHandlerRegistry registry);

    /**
     * Maps annotated methods only on the explicitly supplied objects. This
     * method never scans the application classpath.
     */
    static RemoteHandlerProvider annotated(Object... handlerObjects) {
        return new AnnotatedRemoteHandlerProvider(handlerObjects);
    }
}
