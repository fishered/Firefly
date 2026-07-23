package com.firefly.plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Read-only effective server configuration exposed to trusted plugins. */
public final class FireflyPluginConfiguration {
    private static final FireflyPluginConfiguration EMPTY =
            new FireflyPluginConfiguration(Map.of(), Map.of());

    private final Map<String, String> properties;
    private final Map<String, String> environment;

    public FireflyPluginConfiguration(Map<String, String> properties, Map<String, String> environment) {
        this.properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    public static FireflyPluginConfiguration empty() {
        return EMPTY;
    }

    public Optional<String> property(String name) {
        Objects.requireNonNull(name, "name");
        String value = properties.get(name);
        if (value == null) {
            value = environment.get(environmentName(name));
        }
        return Optional.ofNullable(value).map(String::trim).filter(candidate -> !candidate.isEmpty());
    }

    public String property(String name, String defaultValue) {
        return property(name).orElse(defaultValue);
    }

    public Optional<String> pluginProperty(String pluginId, String name) {
        String normalizedId = requireToken(pluginId, "pluginId");
        String normalizedName = requireToken(name, "name");
        return property("firefly.plugin." + normalizedId + "." + normalizedName);
    }

    public String pluginProperty(String pluginId, String name, String defaultValue) {
        return pluginProperty(pluginId, name).orElse(defaultValue);
    }

    public boolean pluginBoolean(String pluginId, String name, boolean defaultValue) {
        return pluginProperty(pluginId, name).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    public int pluginInt(String pluginId, String name, int defaultValue) {
        return pluginProperty(pluginId, name).map(Integer::parseInt).orElse(defaultValue);
    }

    private static String environmentName(String propertyName) {
        return propertyName.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static String requireToken(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
