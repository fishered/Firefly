package com.firefly.store.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PostgreSqlInitScriptTest {
    private static final String RESOURCE = "/com/firefly/store/jdbc/schema/postgresql.sql";
    private static final Pattern INSERT_TARGET = Pattern.compile("(?is)^insert\\s+into\\s+([a-z0-9_]+)");
    private static final Set<String> REQUIRED_TABLES = Set.of(
            "firefly_schema_version",
            "firefly_node",
            "firefly_shard_lease",
            "firefly_cluster_metadata",
            "firefly_executor",
            "firefly_job_group",
            "firefly_job",
            "firefly_execution",
            "firefly_execution_target",
            "firefly_dispatch_outbox",
            "firefly_executor_instance_location",
            "firefly_audit_log",
            "firefly_job_history",
            "firefly_user",
            "firefly_integration_key"
    );
    private static final Set<String> ALLOWED_INSERT_TARGETS = Set.of(
            "firefly_schema_version",
            "firefly_cluster_metadata",
            "firefly_user"
    );

    @Test
    void packagesTheCanonicalRepositoryScriptWithoutModification() throws IOException {
        String source = Files.readString(repositoryScript(), StandardCharsets.UTF_8);
        try (InputStream inputStream = PostgreSqlInitScriptTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(inputStream, "PostgreSQL init script must be packaged in the JDBC artifact");
            assertEquals(source, new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void containsOnlyTheObjectsAndSeedDataRequiredByFirefly() {
        var statements = JdbcSchemaScript.load(JdbcDialect.POSTGRESQL);
        Set<String> tables = statements.stream()
                .map(PostgreSqlInitScriptTest::createdTable)
                .filter(table -> table != null)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(REQUIRED_TABLES, tables);

        var createStatements = statements.stream()
                .map(statement -> statement.toLowerCase(Locale.ROOT))
                .filter(statement -> statement.startsWith("create "))
                .toList();
        assertTrue(createStatements.stream().allMatch(statement ->
                statement.startsWith("create table if not exists firefly_")
                        || statement.startsWith("create index if not exists idx_firefly_")));

        var inserts = statements.stream().map(INSERT_TARGET::matcher).filter(Matcher::find).toList();
        assertTrue(inserts.stream().map(matcher -> matcher.group(1)).allMatch(ALLOWED_INSERT_TARGETS::contains));
        assertEquals(JdbcSchema.CURRENT_VERSION, inserts.stream()
                .filter(matcher -> matcher.group(1).equals("firefly_schema_version")).count());
        assertEquals(2, inserts.stream()
                .filter(matcher -> matcher.group(1).equals("firefly_cluster_metadata")).count());
        assertEquals(1, inserts.stream().filter(matcher -> matcher.group(1).equals("firefly_user")).count());

        String normalized = String.join("\n", statements).toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("'scheduler.shard-count', '32'"));
        assertTrue(normalized.contains("'jobs.revision', '0'"));
        assertTrue(normalized.contains("'admin',"));
        assertTrue(normalized.contains("'admin', true, true, 0"));
        assertFalse(normalized.contains("create database "));
        assertFalse(normalized.contains("create role "));
        assertFalse(normalized.contains("create user "));
        assertFalse(normalized.contains("grant "));
        assertFalse(normalized.contains("revoke "));
    }

    private static Path repositoryScript() {
        String configuredPath = System.getProperty("firefly.postgresql.init-script");
        assertNotNull(configuredPath, "Gradle must provide the canonical PostgreSQL script path");
        return Path.of(configuredPath);
    }

    private static String createdTable(String statement) {
        String normalized = statement.toLowerCase(Locale.ROOT);
        String prefix = "create table if not exists ";
        if (!normalized.startsWith(prefix)) return null;
        return normalized.substring(prefix.length()).split("[\\s(]", 2)[0];
    }
}
