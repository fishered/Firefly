package com.firefly.store.jdbc;

import com.firefly.domain.JobDefinition;
import com.firefly.domain.CronSchedule;
import com.firefly.engine.ExecutionCommand;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.store.DispatchType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
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
    private static final Duration DATABASE_RECOVERY_TIMEOUT = Duration.ofSeconds(30);
    private static Network network;
    private static PostgreSQLContainer<?> postgres;
    private static MySQLContainer<?> mysql;
    private static ToxiproxyContainer toxiproxy;
    private static ToxiproxyContainer.ContainerProxy postgresProxy;
    private static ToxiproxyContainer.ContainerProxy mysqlProxy;

    @BeforeAll
    static void startContainers() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withNetwork(network)
                .withNetworkAliases("postgres");
        mysql = new MySQLContainer<>("mysql:8.0.36")
                .withDatabaseName("firefly")
                .withUsername("firefly")
                .withPassword("firefly")
                .withNetwork(network)
                .withNetworkAliases("mysql");
        toxiproxy = new ToxiproxyContainer("shopify/toxiproxy:2.1.4")
                .withNetwork(network);
        postgres.start();
        mysql.start();
        toxiproxy.start();
        postgresProxy = toxiproxy.getProxy(postgres, 5432);
        mysqlProxy = toxiproxy.getProxy(mysql, 3306);
    }

    @AfterAll
    static void stopContainers() {
        if (toxiproxy != null) toxiproxy.stop();
        if (mysql != null) mysql.stop();
        if (postgres != null) postgres.stop();
        if (network != null) network.close();
    }

    @Test
    void postgresClaimsEachOutboxRecordAtMostOnceAcrossWorkers() throws Exception {
        assertNoDuplicateClaims(isolatedDataSource(postgres, "postgresql"), "postgresql");
    }

    @Test
    void mysqlClaimsEachOutboxRecordAtMostOnceAcrossWorkers() throws Exception {
        assertNoDuplicateClaims(isolatedDataSource(mysql, "mysql"), "mysql");
    }

    @Test
    void postgresInterruptionFailsFastAndRecoversAfterConnectionRestore() throws Exception {
        assertInterruptionRecovery(
                postgresProxy, isolatedProxiedDataSource(postgres, postgresProxy, "postgresql"), "postgresql"
        );
    }

    @Test
    void mysqlInterruptionFailsFastAndRecoversAfterConnectionRestore() throws Exception {
        assertInterruptionRecovery(
                mysqlProxy, isolatedProxiedDataSource(mysql, mysqlProxy, "mysql"), "mysql"
        );
    }

    @Test
    void postgresRepositoryOperationFailsDuringOutageAndRecoversAfterConnectionRestore() throws Exception {
        assertRepositoryOperationRecovery(
                postgresProxy, isolatedProxiedDataSource(postgres, postgresProxy, "postgresql"),
                "postgresql"
        );
    }

    @Test
    void mysqlRepositoryOperationFailsDuringOutageAndRecoversAfterConnectionRestore() throws Exception {
        assertRepositoryOperationRecovery(
                mysqlProxy, isolatedProxiedDataSource(mysql, mysqlProxy, "mysql"), "mysql"
        );
    }

    @Test
    void postgresReclaimsOutboxAfterClaimLeaseExpires() throws Exception {
        assertOutboxReclaimAfterLeaseExpiry(
                isolatedDataSource(postgres, "postgresql"), "postgresql"
        );
    }

    @Test
    void mysqlReclaimsOutboxAfterClaimLeaseExpires() throws Exception {
        assertOutboxReclaimAfterLeaseExpiry(
                isolatedDataSource(mysql, "mysql"), "mysql"
        );
    }

    @Test
    void postgresShardLeaseRecoversAfterConnectionRestore() throws Exception {
        assertShardLeaseRecoversAfterConnectionRestore(
                postgresProxy, isolatedProxiedDataSource(postgres, postgresProxy, "postgresql"), "postgresql"
        );
    }

    @Test
    void mysqlShardLeaseRecoversAfterConnectionRestore() throws Exception {
        assertShardLeaseRecoversAfterConnectionRestore(
                mysqlProxy, isolatedProxiedDataSource(mysql, mysqlProxy, "mysql"), "mysql"
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
                .id("real-db-" + suffix).name("real db").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
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
            ToxiproxyContainer.ContainerProxy proxy,
            DataSource dataSource,
            String dialect
    ) throws Exception {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        interruptConnection(proxy, dataSource, () -> assertThrows(
                RuntimeException.class,
                () -> JdbcSchema.validate(dataSource, JdbcSchemaOptions.of(dialect))
        ));
        JdbcSchema.validate(dataSource, JdbcSchemaOptions.of(dialect));
    }

    private void assertRepositoryOperationRecovery(
            ToxiproxyContainer.ContainerProxy proxy,
            DataSource dataSource,
            String dialect
    ) throws Exception {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource);
        Instant now = Instant.now().minusSeconds(1);
        String suffix = UUID.randomUUID().toString();
        JobDefinition job = JobDefinition.builder()
                .id("outage-operation-" + suffix)
                .name("outage operation")
                .handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *"))
                .build();
        jobs.save(job, now.plusSeconds(60));

        interruptConnection(proxy, dataSource, () -> {
            assertThrows(RuntimeException.class, () -> jobs.list());
            assertThrows(RuntimeException.class, () -> jobs.setEnabled(job.id(), false));
        });

        assertEquals(job.id(), jobs.find(job.id()).orElseThrow().definition().id());
        assertTrue(jobs.setEnabled(job.id(), false));
        assertTrue(jobs.enqueueManual(new ExecutionCommand(
                "outage-recovered-execution-" + suffix, job, now, now, "node-a", 1L
        )));
        assertTrue(new JdbcExecutionRepository(dataSource)
                .findExecution("outage-recovered-execution-" + suffix).isPresent());
    }

    private void assertOutboxReclaimAfterLeaseExpiry(DataSource dataSource, String dialect) throws Exception {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource);
        Instant now = Instant.now().minusSeconds(1);
        String suffix = UUID.randomUUID().toString();
        JobDefinition job = JobDefinition.builder()
                .id("reclaim-" + suffix).name("reclaim").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
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

    private void assertShardLeaseRecoversAfterConnectionRestore(
            ToxiproxyContainer.ContainerProxy proxy,
            DataSource dataSource,
            String dialect
    ) throws Exception {
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        JdbcShardManager shards = new JdbcShardManager(dataSource);
        assertTrue(shards.acquire(7, "node-a", Instant.now(), Duration.ofSeconds(30)).isPresent());
        interruptConnection(proxy, dataSource, () -> assertThrows(
                RuntimeException.class,
                () -> shards.acquire(7, "node-b", Instant.now(), Duration.ofMillis(1))
        ));
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of(dialect));
        assertTrue(shards.acquire(7, "node-a", Instant.now(), Duration.ofSeconds(30)).isPresent());
    }

    private void interruptConnection(
            ToxiproxyContainer.ContainerProxy proxy,
            DataSource dataSource,
            Runnable whileUnavailable
    ) throws Exception {
        proxy.setConnectionCut(true);
        try {
            whileUnavailable.run();
        } finally {
            proxy.setConnectionCut(false);
            awaitDatabase(dataSource);
        }
    }

    private void awaitDatabase(DataSource dataSource) {
        Instant deadline = Instant.now().plus(DATABASE_RECOVERY_TIMEOUT);
        SQLException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = dataSource.getConnection()) {
                if (connection.isValid(2)) return;
            } catch (SQLException e) {
                lastFailure = e;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for database connection recovery", e);
            }
        }
        throw new AssertionError(
                "database connection did not recover within " + DATABASE_RECOVERY_TIMEOUT.toSeconds() + " seconds",
                lastFailure
        );
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
        return dataSource(
                isolatedJdbcUrl(container, dialect),
                container.getUsername(),
                container.getPassword()
        );
    }

    private String isolatedJdbcUrl(
            org.testcontainers.containers.JdbcDatabaseContainer<?> container,
            String dialect
    ) throws SQLException {
        String database = "firefly_" + UUID.randomUUID().toString().replace("-", "");
        String adminUsername = dialect.equals("mysql") ? "root" : container.getUsername();
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), adminUsername, container.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
            if (dialect.equals("mysql")) {
                statement.execute("grant all privileges on " + database + ".* to '"
                        + container.getUsername() + "'@'%'");
            }
        }
        return withDatabase(container.getJdbcUrl(), database);
    }

    private DataSource isolatedProxiedDataSource(
            org.testcontainers.containers.JdbcDatabaseContainer<?> container,
            ToxiproxyContainer.ContainerProxy proxy,
            String dialect
    ) throws SQLException {
        String directEndpoint = container.getHost() + ":"
                + container.getMappedPort(container.getExposedPorts().getFirst());
        String proxyEndpoint = proxy.getContainerIpAddress() + ":" + proxy.getProxyPort();
        String proxiedUrl = isolatedJdbcUrl(container, dialect).replace(directEndpoint, proxyEndpoint);
        String timeoutParameters = proxiedUrl.startsWith("jdbc:mysql:")
                ? "connectTimeout=2000&socketTimeout=2000"
                : "connectTimeout=2&socketTimeout=2";
        return dataSource(
                proxiedUrl + (proxiedUrl.contains("?") ? "&" : "?") + timeoutParameters,
                container.getUsername(),
                container.getPassword()
        );
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
