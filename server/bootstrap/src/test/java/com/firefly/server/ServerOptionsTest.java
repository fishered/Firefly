package com.firefly.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerOptionsTest {
    @Test
    void disablesOptionalPluginsByDefault() {
        ServerOptions options = ServerOptions.parse(new String[0], Map.of());

        assertEquals(ServerNodeMode.STANDALONE, options.nodeMode());
        assertEquals("firefly-standalone", options.nodeName());
        assertEquals(Set.of(ServerNodeRole.SCHEDULER), options.nodeRoles());
        assertEquals(Set.of(), options.plugins());
        assertFalse(options.demoEnabled());
        assertFalse(options.adminHttpEnabled());
        assertFalse(options.prometheusMetricsEnabled());
        assertFalse(options.nettyExecutorGatewayEnabled());
        assertEquals("127.0.0.1", options.adminHttpHost());
        assertEquals(9710, options.adminHttpPort());
        assertEquals("127.0.0.1", options.prometheusMetricsHost());
        assertEquals(9711, options.prometheusMetricsPort());
        assertEquals(9700, options.nettyExecutorGatewayPort());
        assertEquals("memory", options.store().type());
        assertEquals(32, options.schedulerShards().shardCount());
        assertEquals(java.time.Duration.ofSeconds(1), options.schedulerCoordination().reconcileInterval());
        assertEquals(java.time.Duration.ofSeconds(5), options.schedulerCoordination().nodeTimeout());
        assertEquals(java.time.Duration.ofSeconds(10), options.schedulerCoordination().leaseDuration());
    }

    @Test
    void enablesPluginsFromCommandLineFlags() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.node.name=test-node-1",
                "--firefly.demo.enabled=true",
                "--firefly.node.mode=cluster",
                "--firefly.admin-http.enabled=true",
                "--firefly.admin-http.host=0.0.0.0",
                "--firefly.admin-http.port=9810",
                "--firefly.metrics.prometheus.enabled=true",
                "--firefly.metrics.prometheus.host=0.0.0.0",
                "--firefly.metrics.prometheus.port=9811",
                "--firefly.executor.gateway.netty.enabled=true",
                "--firefly.executor.gateway.netty.port=9800",
                "--firefly.store.type=jdbc",
                "--firefly.jdbc.url=jdbc:h2:mem:server-options-cluster"
        }, Map.of());

        assertEquals(ServerNodeMode.CLUSTER, options.nodeMode());
        assertEquals("test-node-1", options.nodeName());
        assertEquals(Set.of(ServerNodeRole.API, ServerNodeRole.GATEWAY, ServerNodeRole.SCHEDULER), options.nodeRoles());
        assertTrue(options.demoEnabled());
        assertTrue(options.adminHttpEnabled());
        assertEquals("0.0.0.0", options.adminHttpHost());
        assertEquals(9810, options.adminHttpPort());
        assertTrue(options.prometheusMetricsEnabled());
        assertEquals("0.0.0.0", options.prometheusMetricsHost());
        assertEquals(9811, options.prometheusMetricsPort());
        assertTrue(options.nettyExecutorGatewayEnabled());
        assertEquals(9800, options.nettyExecutorGatewayPort());
        assertEquals("jdbc", options.store().type());
    }

    @Test
    void enablesPluginsFromPluginList() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.plugins=admin-http,metrics-prometheus"
        }, Map.of());

        assertEquals(Set.of(ServerPlugin.ADMIN_HTTP, ServerPlugin.METRICS_PROMETHEUS), options.plugins());
        assertTrue(options.adminHttpEnabled());
        assertTrue(options.prometheusMetricsEnabled());
    }

    @Test
    void readsEnvironmentWhenFlagsAreAbsent() {
        ServerOptions options = ServerOptions.parse(new String[0], Map.of(
                "FIREFLY_DEMO_ENABLED", "true",
                "FIREFLY_NODE_MODE", "cluster",
                "FIREFLY_NODE_NAME", "env-node-1",
                "FIREFLY_PLUGINS", "admin-http",
                "FIREFLY_ADMIN_HTTP_ENABLED", "true",
                "FIREFLY_METRICS_PROMETHEUS_ENABLED", "true",
                "FIREFLY_EXECUTOR_GATEWAY_NETTY_ENABLED", "true",
                "FIREFLY_STORE_TYPE", "jdbc",
                "FIREFLY_JDBC_URL", "jdbc:h2:mem:server-options-env"
        ));

        assertEquals(ServerNodeMode.CLUSTER, options.nodeMode());
        assertEquals("env-node-1", options.nodeName());
        assertEquals(Set.of(ServerNodeRole.API, ServerNodeRole.GATEWAY, ServerNodeRole.SCHEDULER), options.nodeRoles());
        assertEquals(Set.of(ServerPlugin.ADMIN_HTTP), options.plugins());
        assertTrue(options.demoEnabled());
        assertTrue(options.adminHttpEnabled());
        assertTrue(options.prometheusMetricsEnabled());
        assertTrue(options.nettyExecutorGatewayEnabled());
    }

    @Test
    void readsPropertiesConfigFile(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.node.mode=cluster
                firefly.node.name=config-node-1
                firefly.plugins=admin-http,metrics-prometheus
                firefly.demo.enabled=true
                firefly.admin-http.port=9810
                firefly.metrics.prometheus.port=9811
                firefly.executor.gateway.netty.enabled=true
                firefly.executor.gateway.netty.port=9800
                firefly.store.type=jdbc
                firefly.jdbc.url=jdbc:h2:mem:server-options-config
                """);

        ServerOptions options = ServerOptions.parse(new String[]{"--firefly.config=" + config}, Map.of());

        assertEquals(ServerNodeMode.CLUSTER, options.nodeMode());
        assertEquals("config-node-1", options.nodeName());
        assertEquals(Set.of(ServerNodeRole.API, ServerNodeRole.GATEWAY, ServerNodeRole.SCHEDULER), options.nodeRoles());
        assertEquals(Set.of(ServerPlugin.ADMIN_HTTP, ServerPlugin.METRICS_PROMETHEUS), options.plugins());
        assertTrue(options.demoEnabled());
        assertTrue(options.adminHttpEnabled());
        assertEquals(9810, options.adminHttpPort());
        assertTrue(options.prometheusMetricsEnabled());
        assertEquals(9811, options.prometheusMetricsPort());
        assertTrue(options.nettyExecutorGatewayEnabled());
        assertEquals(9800, options.nettyExecutorGatewayPort());
        assertEquals("jdbc", options.store().type());
    }

    @Test
    void commandLineAndEnvironmentOverrideConfigFile(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.node.mode=standalone
                firefly.plugins=metrics-prometheus
                firefly.admin-http.port=9810
                firefly.executor.gateway.netty.enabled=false
                """);

        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.config=" + config,
                "--firefly.admin-http.port=9910"
        }, Map.of(
                "FIREFLY_NODE_MODE", "cluster",
                "FIREFLY_NODE_NAME", "override-node-1",
                "FIREFLY_ADMIN_HTTP_ENABLED", "true",
                "FIREFLY_STORE_TYPE", "jdbc",
                "FIREFLY_JDBC_URL", "jdbc:h2:mem:server-options-override"
        ));

        assertEquals(ServerNodeMode.CLUSTER, options.nodeMode());
        assertEquals("override-node-1", options.nodeName());
        assertEquals(Set.of(ServerPlugin.METRICS_PROMETHEUS), options.plugins());
        assertTrue(options.adminHttpEnabled());
        assertEquals(9910, options.adminHttpPort());
        assertFalse(options.nettyExecutorGatewayEnabled());
    }

    @Test
    void explicitNodeRolesDriveServerDuties() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.node.roles=api,gateway"
        }, Map.of());

        assertEquals(Set.of(ServerNodeRole.API, ServerNodeRole.GATEWAY), options.nodeRoles());
        assertTrue(options.adminHttpEnabled());
        assertTrue(options.nettyExecutorGatewayEnabled());
        assertFalse(options.nodeRoles().contains(ServerNodeRole.SCHEDULER));
    }

    @Test
    void rejectsClusterModeWithMemoryStore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{
                        "--firefly.node.mode=cluster",
                        "--firefly.node.name=cluster-node-1",
                        "--firefly.store.type=memory"
                }, Map.of())
        );
    }

    @Test
    void rejectsClusterModeWithoutNodeName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{
                        "--firefly.node.mode=cluster",
                        "--firefly.store.type=jdbc",
                        "--firefly.jdbc.url=jdbc:h2:mem:missing-node-name"
                }, Map.of())
        );
    }

    @Test
    void rejectsAdminHttpWhenExplicitRolesOmitApi() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{
                        "--firefly.node.roles=scheduler",
                        "--firefly.admin-http.enabled=true"
                }, Map.of())
        );
    }

    @Test
    void readsJdbcStoreOptionsFromConfigFile(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.store.type=jdbc
                firefly.jdbc.url=jdbc:h2:mem:server-options
                firefly.jdbc.username=sa
                firefly.jdbc.dialect=h2
                firefly.jdbc.schema.mode=validate
                """);

        ServerOptions options = ServerOptions.parse(new String[]{"--firefly.config=" + config}, Map.of());

        assertTrue(options.store().jdbcEnabled());
        assertEquals("jdbc:h2:mem:server-options", options.store().jdbcUrl());
        assertEquals("sa", options.store().jdbcUsername());
        assertEquals("h2", options.store().jdbcDialect());
        assertEquals("validate", options.store().jdbcSchemaMode());
    }

    @Test
    void mergesConfigProfileFromMainConfig(@TempDir Path tempDir) throws IOException {
        Path profiles = Files.createDirectories(tempDir.resolve("profiles"));
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.config.profile=pg
                firefly.plugins=admin-http
                firefly.admin-http.port=9810
                firefly.store.type=memory
                """);
        Files.writeString(profiles.resolve("pg.properties"), """
                firefly.store.type=jdbc
                firefly.jdbc.url=jdbc:postgresql://127.0.0.1:5432/firefly
                firefly.jdbc.username=postgres
                firefly.jdbc.password=123456
                firefly.jdbc.dialect=postgresql
                firefly.jdbc.schema.mode=initialize-if-empty
                """);

        ServerOptions options = ServerOptions.parse(new String[]{"--firefly.config=" + config}, Map.of());

        assertEquals(Set.of(ServerPlugin.ADMIN_HTTP), options.plugins());
        assertEquals(9810, options.adminHttpPort());
        assertTrue(options.store().jdbcEnabled());
        assertEquals("jdbc:postgresql://127.0.0.1:5432/firefly", options.store().jdbcUrl());
        assertEquals("postgresql", options.store().jdbcDialect());
    }

    @Test
    void commandLineConfigProfileOverridesMainConfigProfile(@TempDir Path tempDir) throws IOException {
        Path profiles = Files.createDirectories(tempDir.resolve("profiles"));
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.config.profile=pg
                firefly.store.type=memory
                """);
        Files.writeString(profiles.resolve("pg.properties"), """
                firefly.store.type=jdbc
                firefly.jdbc.url=jdbc:postgresql://127.0.0.1:5432/firefly
                firefly.jdbc.dialect=postgresql
                """);
        Files.writeString(profiles.resolve("h2.properties"), """
                firefly.store.type=jdbc
                firefly.jdbc.url=jdbc:h2:file:./data/firefly;AUTO_SERVER=TRUE
                firefly.jdbc.username=sa
                firefly.jdbc.dialect=h2
                """);

        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.config=" + config,
                "--firefly.config.profile=h2"
        }, Map.of());

        assertTrue(options.store().jdbcEnabled());
        assertEquals("jdbc:h2:file:./data/firefly;AUTO_SERVER=TRUE", options.store().jdbcUrl());
        assertEquals("h2", options.store().jdbcDialect());
    }

    @Test
    void rejectsMissingConfigProfile(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, "firefly.config.profile=missing\n");

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{"--firefly.config=" + config}, Map.of())
        );
    }

    @Test
    void acceptsLegacyAdminWebFlags() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.plugins=admin-web",
                "--firefly.admin-web.enabled=true",
                "--firefly.admin-web.port=9810"
        }, Map.of());

        assertEquals(Set.of(ServerPlugin.ADMIN_HTTP), options.plugins());
        assertTrue(options.adminHttpEnabled());
        assertEquals(9810, options.adminHttpPort());
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{"--firefly.admin-http.port=0"}, Map.of())
        );
    }

    @Test
    void acceptsExternalPluginIdsAndExposesTheirConfiguration() {
        ServerOptions options = ServerOptions.parse(new String[]{
                "--firefly.plugins=acme-alerts",
                "--firefly.plugin.acme-alerts.endpoint=http://127.0.0.1:9999"
        }, Map.of());

        assertEquals(Set.of(new ServerPlugin("acme-alerts")), options.plugins());
        assertEquals("http://127.0.0.1:9999", options.pluginConfiguration()
                .pluginProperty("acme-alerts", "endpoint").orElseThrow());
    }

    @Test
    void rejectsCoordinationTimeoutThatIsNotShorterThanTheLease() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{
                        "--firefly.scheduler.coordination.node-timeout=PT10S",
                        "--firefly.scheduler.coordination.lease-duration=PT10S"
                }, Map.of())
        );
    }

    @Test
    void readsSchedulerShardCountFromEnvironment() {
        ServerOptions options = ServerOptions.parse(
                new String[0], Map.of("FIREFLY_SCHEDULER_SHARD_COUNT", "64")
        );

        assertEquals(64, options.schedulerShards().shardCount());
    }

    @Test
    void readsRuntimeTuningFromEnvironment() {
        ServerOptions options = ServerOptions.parse(new String[0], Map.ofEntries(
                Map.entry("FIREFLY_DISPATCH_OUTBOX_POLL_INTERVAL", "PT0.05S"),
                Map.entry("FIREFLY_DISPATCH_OUTBOX_CLAIM_BATCH_SIZE", "75"),
                Map.entry("FIREFLY_DISPATCH_OUTBOX_REMOTE_ACK_TIMEOUT", "PT8S"),
                Map.entry("FIREFLY_EXECUTION_MAINTENANCE_RETENTION", "P14D"),
                Map.entry("FIREFLY_JDBC_CLOCK_SYNC_INTERVAL", "PT20S"),
                Map.entry("FIREFLY_JDBC_CLOCK_DRIFT_WARNING_THRESHOLD", "PT0.5S"),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_RESULT_QUEUE_CAPACITY", "2048"),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_MAX_FRAME_LENGTH", "131072"),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_RELOAD_INTERVAL", "PT15S"),
                Map.entry("FIREFLY_SCHEDULER_MAX_DUE_RECORDS_PER_TICK", "2500"),
                Map.entry("FIREFLY_SCHEDULER_MAX_IDLE_WAKEUP", "PT0.25S")
        ));

        assertEquals(Duration.ofMillis(50), options.runtimeOptions().dispatchOutbox().pollInterval());
        assertEquals(75, options.runtimeOptions().dispatchOutbox().claimBatchSize());
        assertEquals(Duration.ofSeconds(8), options.runtimeOptions().dispatchOutbox().remoteAckTimeout());
        assertEquals(Duration.ofDays(14), options.runtimeOptions().executionMaintenance().retention());
        assertEquals(Duration.ofSeconds(20), options.runtimeOptions().jdbcClock().syncInterval());
        assertEquals(Duration.ofMillis(500), options.runtimeOptions().jdbcClock().driftWarningThreshold());
        assertEquals(2048, options.runtimeOptions().nettyGateway().resultQueueCapacity());
        assertEquals(131072, options.runtimeOptions().nettyGateway().maxFrameLength());
        assertEquals(Duration.ofSeconds(15), options.runtimeOptions().nettyGateway().tlsReloadInterval());
        assertEquals(2500, options.runtimeOptions().schedulerEngine().maxDueRecordsPerTick());
        assertEquals(Duration.ofMillis(250), options.runtimeOptions().schedulerEngine().maxIdleWakeup());
    }

    @Test
    void readsSecurityOptionsFromEnvironment(@TempDir Path tempDir) throws IOException {
        Path certificate = Files.writeString(tempDir.resolve("server.crt"), "certificate");
        Path privateKey = Files.writeString(tempDir.resolve("server.key"), "private-key");
        Path trust = Files.writeString(tempDir.resolve("ca.crt"), "ca");

        ServerOptions options = ServerOptions.parse(new String[0], Map.ofEntries(
                Map.entry("FIREFLY_ADMIN_HTTP_READER_TOKEN", "reader"),
                Map.entry("FIREFLY_ADMIN_HTTP_OPERATOR_TOKEN", "operator"),
                Map.entry("FIREFLY_ADMIN_HTTP_ADMIN_TOKEN", "admin"),
                Map.entry("FIREFLY_SECURITY_JWT_ENABLED", "true"),
                Map.entry("FIREFLY_SECURITY_JWT_SECRET", "01234567890123456789012345678901"),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_ENABLED", "true"),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_CERTIFICATE_CHAIN", certificate.toString()),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_PRIVATE_KEY", privateKey.toString()),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_TRUST_CERTIFICATES", trust.toString()),
                Map.entry("FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_REQUIRE_CLIENT_AUTH", "true")
        ));

        assertEquals("reader", options.runtimeOptions().adminAuthorization().readerToken());
        assertEquals("operator", options.runtimeOptions().adminAuthorization().operatorToken());
        assertEquals("admin", options.runtimeOptions().adminAuthorization().adminToken());
        assertTrue(options.runtimeOptions().jwtSecurity().enabled());
        assertTrue(options.runtimeOptions().nettyGateway().tls().enabled());
        assertEquals(certificate.toAbsolutePath().normalize(),
                options.runtimeOptions().nettyGateway().tls().certificateChain());
        assertTrue(options.runtimeOptions().nettyGateway().tls().requireClientAuth());
    }

    @Test
    void disabledJwtIgnoresIncompleteClientConfiguration(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.security.jwt.enabled=false
                firefly.security.jwt.clients=admin
                firefly.security.jwt.client.admin.secret=
                """);

        ServerOptions options = ServerOptions.parse(new String[]{"--firefly.config=" + config}, Map.of());

        assertFalse(options.runtimeOptions().jwtSecurity().enabled());
    }

    @Test
    void ignoresRemovedMachineJwtClientProperties(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.security.jwt.enabled=true
                firefly.security.jwt.secret=01234567890123456789012345678901
                firefly.security.jwt.clients=admin
                firefly.security.jwt.client.admin.secret=
                firefly.security.jwt.client.admin.roles=ADMIN
                """);

        ServerOptions options = ServerOptions.parse(
                new String[]{"--firefly.config=" + config}, Map.of()
        );
        assertTrue(options.runtimeOptions().jwtSecurity().enabled());
    }

    @Test
    void clusterModeRejectsBundledDevelopmentJwtCredentials(@TempDir Path tempDir) throws IOException {
        Path config = tempDir.resolve("firefly-server.properties");
        Files.writeString(config, """
                firefly.node.mode=cluster
                firefly.node.name=node-a
                firefly.node.roles=scheduler
                firefly.store.type=jdbc
                firefly.jdbc.url=jdbc:h2:mem:cluster-security
                firefly.jdbc.dialect=h2
                firefly.security.jwt.enabled=true
                firefly.security.jwt.secret=firefly-local-development-signing-secret-unsafe-change-me
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                ServerOptions.parse(new String[]{"--firefly.config=" + config}, Map.of()));

        assertTrue(failure.getMessage().contains("rejects bundled development security credentials"));
    }

    @Test
    void rejectsMissingConfigFile() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{"--firefly.config=missing-firefly.properties"}, Map.of())
        );
    }
}
