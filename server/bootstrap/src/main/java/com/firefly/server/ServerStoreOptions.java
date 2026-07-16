package com.firefly.server;

import java.util.Locale;
import java.util.Set;

public record ServerStoreOptions(
        String type,
        String jdbcUrl,
        String jdbcUsername,
        String jdbcPassword,
        String jdbcDialect,
        String jdbcSchemaMode
) {
    private static final String MEMORY = "memory";
    private static final String JDBC = "jdbc";
    private static final Set<String> SCHEMA_MODES = Set.of("initialize-if-empty", "initialize", "validate", "none");

    public ServerStoreOptions {
        type = normalize(type, MEMORY);
        jdbcUsername = normalize(jdbcUsername, "");
        jdbcPassword = jdbcPassword == null ? "" : jdbcPassword;
        jdbcDialect = normalize(jdbcDialect, "auto");
        jdbcSchemaMode = normalize(jdbcSchemaMode, "initialize-if-empty");
        if (!MEMORY.equals(type) && !JDBC.equals(type)) {
            throw new IllegalArgumentException("unsupported store type: " + type);
        }
        if (JDBC.equals(type) && (jdbcUrl == null || jdbcUrl.isBlank())) {
            throw new IllegalArgumentException("firefly.jdbc.url is required when firefly.store.type=jdbc");
        }
        if (!SCHEMA_MODES.contains(jdbcSchemaMode)) {
            throw new IllegalArgumentException("unsupported jdbc schema mode: " + jdbcSchemaMode);
        }
    }

    public static ServerStoreOptions memory() {
        return new ServerStoreOptions(MEMORY, null, "", "", "auto", "none");
    }

    public boolean jdbcEnabled() {
        return JDBC.equals(type);
    }

    public boolean jdbcSchemaInitialize() {
        return "initialize".equals(jdbcSchemaMode) || "initialize-if-empty".equals(jdbcSchemaMode);
    }

    private static String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
