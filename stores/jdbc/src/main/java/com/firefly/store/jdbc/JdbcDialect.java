package com.firefly.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;

enum JdbcDialect {
    H2("h2"),
    POSTGRESQL("postgresql"),
    MYSQL("mysql");

    private final String id;

    JdbcDialect(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static JdbcDialect resolve(Connection connection, JdbcSchemaOptions options) throws SQLException {
        if (!options.autoDetect()) {
            return byId(options.dialect());
        }
        String productName = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (productName.contains("h2")) {
            return H2;
        }
        if (productName.contains("postgres")) {
            return POSTGRESQL;
        }
        if (productName.contains("mysql") || productName.contains("mariadb")) {
            return MYSQL;
        }
        throw new JdbcException("unsupported jdbc database product: " + productName);
    }

    private static JdbcDialect byId(String id) {
        return Arrays.stream(values())
                .filter(dialect -> dialect.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new JdbcException("unsupported jdbc dialect: " + id));
    }
}
