package com.firefly.store.jdbc;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.security.FireflyRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("real-database")
final class PostgreSqlInitScriptRealDatabaseTest {
    @Test
    void initializesAServiceReadyPostgreSqlDatabase() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            DataSource dataSource = dataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
            );

            executeRepositoryScript(dataSource);
            JdbcSchema.validate(dataSource, JdbcSchemaOptions.of("postgresql"));

            var admin = new JdbcAdminUserRepository(dataSource).find("admin").orElseThrow();
            assertTrue(admin.enabled());
            assertTrue(admin.passwordChangeRequired());
            assertEquals(java.util.Set.of(FireflyRole.ADMIN), admin.roles());

            JdbcJobRepository jobs = new JdbcJobRepository(dataSource);
            JobDefinition definition = JobDefinition.builder()
                    .id("init-script-smoke-test")
                    .name("Init script smoke test")
                    .handlerName("smoke-test")
                    .schedule(new CronSchedule("0 * * * * *"))
                    .build();
            Instant nextFireTime = Instant.parse("2026-07-29T12:00:00Z");
            jobs.save(definition, nextFireTime);
            assertEquals(definition, jobs.find(definition.id()).orElseThrow().definition());
            assertEquals(nextFireTime, jobs.find(definition.id()).orElseThrow().nextFireTime());
        }
    }

    private void executeRepositoryScript(DataSource dataSource) throws Exception {
        Path script = Path.of(System.getProperty("firefly.postgresql.init-script"));
        try (InputStream inputStream = Files.newInputStream(script);
             Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (String sql : JdbcSchemaScript.parse(inputStream)) {
                statement.execute(sql);
            }
        }
    }

    private DataSource dataSource(String url, String username, String password) {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, username, password);
            }
            @Override public Connection getConnection(String user, String pass) throws SQLException {
                return DriverManager.getConnection(url, user, pass);
            }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not wrapped");
            }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
        };
    }
}
