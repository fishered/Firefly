package com.firefly.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

final class ServerConfigFile {
    private ServerConfigFile() {
    }

    static Map<String, String> load(String configPath, String profileOverride) {
        if (configPath == null || configPath.isBlank()) {
            return Map.of();
        }
        Path path = Path.of(configPath.trim());
        Map<String, String> values = new HashMap<>(loadProperties(path));
        String profile = profileOverride == null || profileOverride.isBlank()
                ? values.get("firefly.config.profile")
                : profileOverride;
        if (profile != null && !profile.isBlank() && !"none".equalsIgnoreCase(profile)) {
            values.putAll(loadProperties(profilePath(path, profile)));
            values.put("firefly.config.profile", profile);
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> loadProperties(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("firefly config file does not exist: " + path.toAbsolutePath());
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read firefly config file: " + path.toAbsolutePath(), e);
        }
        Map<String, String> values = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            String value = Objects.toString(properties.getProperty(name), "").trim();
            if (!value.isEmpty()) {
                values.put(name.trim(), value);
            }
        }
        return Map.copyOf(values);
    }

    private static Path profilePath(Path configPath, String profile) {
        Path configDir = configPath.toAbsolutePath().getParent();
        Path profileFile = configDir.resolve("profiles").resolve(profile + ".properties");
        if (Files.exists(profileFile)) {
            return profileFile;
        }
        return configDir.resolve("firefly-server-" + profile + ".properties");
    }
}
