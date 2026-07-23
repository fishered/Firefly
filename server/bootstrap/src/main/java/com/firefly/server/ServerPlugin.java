package com.firefly.server;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A configured plugin id. Built-in ids are constants; external ids remain open-ended. */
public record ServerPlugin(String id) {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    public static final ServerPlugin ADMIN_HTTP = new ServerPlugin("admin-http");
    public static final ServerPlugin METRICS_PROMETHEUS = new ServerPlugin("metrics-prometheus");

    public ServerPlugin {
        Objects.requireNonNull(id, "id");
        id = normalize(id);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid server plugin id: " + id);
        }
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
        String normalized = normalize(value);
        if ("admin-web".equals(normalized)) {
            return ADMIN_HTTP;
        }
        if (ADMIN_HTTP.id.equals(normalized)) return ADMIN_HTTP;
        if (METRICS_PROMETHEUS.id.equals(normalized)) return METRICS_PROMETHEUS;
        return new ServerPlugin(normalized);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("server plugin id must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
