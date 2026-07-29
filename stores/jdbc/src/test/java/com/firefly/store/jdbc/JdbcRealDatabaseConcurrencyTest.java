package com.firefly.store.jdbc;

import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.store.DispatchType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("real-database")
class JdbcRealDatabaseConcurrencyTest {
    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;

    @BeforeAll
    static void startContainers() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        mysql = new MySQLContainer<>("mysql:8.0.36")
                .withDatabaseName("firefly")
                .withUsername("firefly")
                .withPassword("firefly");
        postgres.start();
        mysql.start();
    }

    @AfterAll
    static void stopContainers() {
        if (mysql != null) mysql.stop();
        if (postgres != null) postgres.stop();
    }

    @Test
    void postgresClaimsEachOutboxRecordAtMostOnceAcrossWorkers() throws Exception {
        assertNoDuplicateClaims(dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()), "postgresql");
    }

    @Test
    void mysqlClaimsEachOutboxRecordAtMostOnceAcrossWorkers() throws Exception {
        assertNoDuplicateClaims(dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()), "mysql");
    }

    @Test
    void postgresInterruptionFailsFastAndRecoversAfterRestart() throws Exception {
        assertInterruptionRecovery(
                postgres, dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()), "postgresql"
        );
    }

    @Test
    void mysqlInterruptionFailsFastAndRecoversAfterRestart() throws Exception {
        assertInterruptionRecovery(
                mysql, dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()), "mysql"
        );
    }

    @Test
    void postgresReclaimsOutboxAfterClaimLeaseExpires() throws Exception {
        assertOutboxReclaimAfterLeaseExpiry(
                dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()), "postgresql"
        );
    }

    @Test
    void mysqlReclaimsOutboxAfterClaimLeaseExpires() throws Exception {
        assertOutboxReclaimAfterLeaseExpiry(
                dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()), "mysql"
        );
    }

    @Test
    void postgresShardLeaseRecoversAfterDatabaseRestart() throws Exception {
        assertShardLeaseRecoversAfterRestart(
                postgres, dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()), "postgresql"
        );
    }

    @Test
    void mysqlShardLeaseRecoversAfterDatabaseRestart() throws Exception {
        assertShardLeaseRecoversAfterRestart(
                mysql, dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()), "mysql"
        );
    }

    @Test
    void postgresInitializesEmptySchemaConcurrently() throws Exception {
        assertConcurrentSchemaInitialization(isolatedDataSource(postgres, "postgresql"), "postgresql");
    }

    @Test
    void mysqlInitializesEmptySchemaConcurrently() throws Exception {
        assertConcurrentSchemaInitialization(isolatedDataSource(mysql, "mysql"), "mysql");
    }

    private void assertNoDuplicateClaims(DataSource dataSource, String dialect) throws Exception {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource);
        Instant now = Instant.now().minusSeconds(1);
        String suffix = UUID.randomUUID().toString();
        JobDefinition job = JobDefinition.builder()
                .id("real-db-" + suffix).name("real db").handlerName("remote:orders:run").build();
        for (int index = 0; index < 20; index++) {
            String executionId = "real-db-execution-" + suffix + "-" + index;
            assertTrue(jobs.enqueueManual(new ExecutionCommand(executionId, job, now, now, "node-a", 1L)));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> claim(jobs, "worker-a", ready, start));
            var second = pool.submit(() -> claim(jobs, "worker-b", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<DispatchOutboxRecord> claimed = new ArrayList<>();
            claimed.addAll(first.get(20, TimeUnit.SECONDS));
            claimed.addAll(second.get(20, TimeUnit.SECONDS));
            assertEquals(20, claimed.size());
            assertEquals(20, claimed.stream().map(DispatchOutboxRecord::outboxId).collect(java.util.stream.Collectors.toSet()).size());
        } finally {
            pool.shutdownNow();
        }
    }

    private List<DispatchOutboxRecord> claim(
            JdbcJobRepository jobs, String worker, CountDownLatch ready, CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        return jobs.claimDispatches(worker, Instant.now(), 10, java.time.Duration.ofMinutes(1), Set.of(DispatchType.REMOTE));
    }

    private void assertInterruptionRecovery(
            org.testcontainers.containers.JdbcDatabaseContainer<?> container,
            DataSource dataSource,
            String dialect
    ) {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        container.stop();
        try {
            assertThrows(RuntimeException.class, () -> JdbcSchema.validate(dataSource, JdbcSchemaOptions.of(dialect)));
        } finally {
            container.start();
        }
        JdbcSchema.validate(dataSource, JdbcSchemaOptions.of(dialect));
    }

    private void assertOutboxReclaimAfterLeaseExpiry(DataSource dataSource, String dialect) throws Exception {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource);
        Instant now = Instant.now().minusSeconds(1);
        String suffix = UUID.randomUUID().toString();
        JobDefinition job = JobDefinition.builder()
                .id("reclaim-" + suffix).name("reclaim").handlerName("remote:orders:run").build();
        assertTrue(jobs.enqueueManual(new ExecutionCommand(
                "reclaim-execution-" + suffix, job, now, now, "node-a", 1L
        )));

        List<DispatchOutboxRecord> first = jobs.claimDispatches(
                "worker-a", Instant.now(), 1, Duration.ofMillis(500), Set.of(DispatchType.REMOTE)
        );
        assertEquals(1, first.size());
        assertEquals(0, jobs.claimDispatches(
                "worker-b", Instant.now(), 1, Duration.ofSeconds(5), Set.of(DispatchType.REMOTE)
        ).size());

        Thread.sleep(750);

        List<DispatchOutboxRecord> reclaimed = jobs.claimDispatches(
                "worker-b", Instant.now(), 1, Duration.ofSeconds(5), Set.of(DispatchType.REMOTE)
        );
        assertEquals(1, reclaimed.size());
        assertEquals(first.get(0).outboxId(), reclaimed.get(0).outboxId());
    }

    private void assertShardLeaseRecoversAfterRestart(
            org.testcontainers.containers.JdbcDatabaseContainer<?> container,
            DataSource dataSource,
            String dialect
    ) {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        JdbcShardManager shards = new JdbcShardManager(dataSource);
        assertTrue(shards.acquire(7, "node-a", Instant.now(), Duration.ofSeconds(30)).isPresent());
        container.stop();
        try {
            assertThrows(RuntimeException.class, () ->
                    shards.acquire(7, "node-b", Instant.now(), Duration.ofMillis(1)));
        } finally {
            container.start();
        }
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        assertTrue(shards.acquire(7, "node-a", Instant.now(), Duration.ofSeconds(30)).isPresent());
    }

    private void assertConcurrentSchemaInitialization(DataSource dataSource, String dialect) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> initializeAfterBarrier(dataSource, dialect, ready, start));
            var second = pool.submit(() -> initializeAfterBarrier(dataSource, dialect, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        JdbcSchema.validate(dataSource, JdbcSchemaOptions.of(dialect));
    }

    private Void initializeAfterBarrier(
            DataSource dataSource, String dialect, CountDownLatch ready, CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        return null;
    }

    private DataSource isolatedDataSource(
            org.testcontainers.containers.JdbcDatabaseContainer<?> container,
            String dialect
    ) throws SQLException {
        String database = "firefly_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        return dataSource(withDatabase(container.getJdbcUrl(), database), container.getUsername(), container.getPassword());
    }

    private String withDatabase(String jdbcUrl, String database) {
        int slash = jdbcUrl.lastIndexOf('/');
        int query = jdbcUrl.indexOf('?', slash);
        if (query < 0) {
            return jdbcUrl.substring(0, slash + 1) + database;
        }
        return jdbcUrl.substring(0, slash + 1) + database + jdbcUrl.substring(query);
    }

    private DataSource dataSource(String url, String username, String password) {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, username, password);
            }
            @Override public Connection getConnection(String user, String pass) throws SQLException {
                return DriverManager.getConnection(url, user, pass);
            }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not wrapped"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        };
    }
}
