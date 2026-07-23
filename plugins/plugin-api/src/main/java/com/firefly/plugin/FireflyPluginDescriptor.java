package com.firefly.plugin;

/** Read-only runtime metadata exposed to operations tooling. */
public record FireflyPluginDescriptor(
        String id,
        String displayName,
        String version,
        String description,
        String implementationClass,
        String source,
        String status
) { }
