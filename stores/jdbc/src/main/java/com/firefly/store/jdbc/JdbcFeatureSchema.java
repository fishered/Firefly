package com.firefly.store.jdbc;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/** Installs additive scheduling-semantics and batch tables without changing the v12 compatibility contract. */
public final class JdbcFeatureSchema {
    private JdbcFeatureSchema() { }

    public static void initialize(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            JdbcDialect dialect = JdbcDialect.resolve(connection, options);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 13)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 14)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 15)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 16)) statement.execute(sql);
            JdbcSchema.ensureIndex(connection, "firefly_calendar_date_rule", "idx_firefly_calendar_rule_date", "calendar_id, calendar_version, rule_date");
            JdbcSchema.ensureIndex(connection, "firefly_calendar_import", "idx_firefly_calendar_import_status", "calendar_id, status, imported_at");
            JdbcSchema.ensureIndex(connection, "firefly_dependency_wait", "idx_firefly_dependency_wait_next", "next_check_at, job_id");
            JdbcSchema.ensureIndex(connection, "firefly_dependency_gate", "idx_firefly_dependency_gate_due", "status, next_check_at, job_id");
        } catch (SQLException e) {
            throw new JdbcException("failed to initialize feature schema", e);
        }
    }
}
