package com.firefly.plugin;

/**
 * Lifecycle contract for optional Firefly components.
 */
public interface FireflyPlugin extends AutoCloseable {
    String id();

    void start(FireflyPluginContext context);

    @Override
    default void close() {
    }
}
