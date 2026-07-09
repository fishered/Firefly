package com.firefly.store.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Initializes JDBC schema by selecting a dialect-specific SQL resource.
 */
public final class JdbcSchema {
    private JdbcSchema() {
    }

    public static void initialize(DataSource dataSource) {
        initialize(dataSource, JdbcSchemaOptions.auto());
    }

    public static void initialize(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            JdbcDialect dialect = JdbcDialect.resolve(connection, options);
            for (String sql : JdbcSchemaScript.load(dialect)) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to initialize firefly jdbc schema", e);
        }
    }
}
