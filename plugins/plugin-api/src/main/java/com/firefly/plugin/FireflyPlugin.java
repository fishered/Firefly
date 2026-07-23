package com.firefly.plugin;

/**
 * Lifecycle contract for optional Firefly components.
 */
public interface FireflyPlugin extends AutoCloseable {
    String id();

    default String displayName() {
        return id();
    }

    default String version() {
        Package typePackage = getClass().getPackage();
        String implementationVersion = typePackage == null ? null : typePackage.getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "development" : implementationVersion;
    }

    default String description() {
        return "";
    }

    void start(FireflyPluginContext context);

    @Override
    default void close() {
    }
}
