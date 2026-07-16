package com.firefly.server;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public enum ServerNodeRole {
    API,
    GATEWAY,
    SCHEDULER;

    public static Set<ServerNodeRole> parseList(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<ServerNodeRole> roles = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(ServerNodeRole::parse)
                .forEach(roles::add);
        return Set.copyOf(roles);
    }

    private static ServerNodeRole parse(String value) {
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return valueOf(normalized);
    }
}
