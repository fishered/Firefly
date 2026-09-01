package com.firefly.plugin;

import java.util.Objects;

/** Compatibility contract advertised by a Firefly node during rolling upgrades. */
public record FireflyHostCompatibility(
        String fireflyVersion,
        int pluginApiLevel,
        int minimumExecutorProtocol,
        int maximumExecutorProtocol,
        int minimumDatabaseSchema,
        int maximumDatabaseSchema
) {
    public FireflyHostCompatibility {
        fireflyVersion = Objects.requireNonNull(fireflyVersion, "fireflyVersion");
        if (fireflyVersion.isBlank()) throw new IllegalArgumentException("fireflyVersion must not be blank");
        if (pluginApiLevel < 1) throw new IllegalArgumentException("pluginApiLevel must be positive");
        if (minimumExecutorProtocol < 1 || maximumExecutorProtocol < minimumExecutorProtocol) {
            throw new IllegalArgumentException("invalid executor protocol range");
        }
        if (minimumDatabaseSchema < 1 || maximumDatabaseSchema < minimumDatabaseSchema) {
            throw new IllegalArgumentException("invalid database schema range");
        }
    }

    public static FireflyHostCompatibility current() {
        return new FireflyHostCompatibility("1.1.2", FireflyPluginCompatibility.CURRENT_API_LEVEL,
                1, 2, 12, 12);
    }
}
