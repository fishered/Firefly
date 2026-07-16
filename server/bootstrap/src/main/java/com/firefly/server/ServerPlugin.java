package com.firefly.server;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public enum ServerPlugin {
    ADMIN_HTTP("admin-http"),
    METRICS_PROMETHEUS("metrics-prometheus");

    private final String id;

    ServerPlugin(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Set<ServerPlugin> parseList(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(ServerPlugin::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static ServerPlugin parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("admin-web".equals(normalized)) {
            return ADMIN_HTTP;
        }
        return Arrays.stream(values())
                .filter(plugin -> plugin.id.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported server plugin: " + value));
    }
}
