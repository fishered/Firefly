package com.firefly.store.jdbc;

import java.util.Locale;
import java.util.Objects;

/**
 * Controls schema initialization without binding callers to a concrete database.
 */
public record JdbcSchemaOptions(String dialect) {
    public static final String AUTO = "auto";

    public JdbcSchemaOptions {
        Objects.requireNonNull(dialect, "dialect");
        dialect = dialect.trim().toLowerCase(Locale.ROOT);
        if (dialect.isBlank()) {
            throw new IllegalArgumentException("dialect must not be blank");
        }
    }

    public static JdbcSchemaOptions auto() {
        return new JdbcSchemaOptions(AUTO);
    }

    public static JdbcSchemaOptions of(String dialect) {
        return new JdbcSchemaOptions(dialect);
    }

    public boolean autoDetect() {
        return AUTO.equals(dialect);
    }
}
