package com.firefly.server;

import com.firefly.cluster.SchedulerCoordinationOptions;
import com.firefly.cluster.SchedulerShardConfig;
import com.firefly.plugin.FireflyPluginConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parses lightweight server flags without introducing a configuration framework.
 */
public record ServerOptions(
        ServerNodeMode nodeMode,
        String nodeName,
        Set<ServerNodeRole> nodeRoles,
        Set<ServerPlugin> plugins,
        boolean demoEnabled,
        boolean adminHttpEnabled,
        String adminHttpHost,
        int adminHttpPort,
        boolean prometheusMetricsEnabled,
        String prometheusMetricsHost,
        int prometheusMetricsPort,
        boolean nettyExecutorGatewayEnabled,
        int nettyExecutorGatewayPort,
        ServerStoreOptions store,
        SchedulerShardConfig schedulerShards,
        SchedulerCoordinationOptions schedulerCoordination,
        boolean executorDefinitionAutoCreate,
        String adminApiToken,
        ServerRuntimeOptions runtimeOptions,
        FireflyPluginConfiguration pluginConfiguration
) {
    private static final int DEFAULT_ADMIN_HTTP_PORT = 9710;
    private static final int DEFAULT_PROMETHEUS_METRICS_PORT = 9711;
    private static final int DEFAULT_NETTY_EXECUTOR_GATEWAY_PORT = 9700;

    public ServerOptions {
        Objects.requireNonNull(nodeMode, "nodeMode");
        if (nodeName == null || nodeName.isBlank()) {
            throw new IllegalArgumentException("firefly.node.name is required");
        }
        nodeName = nodeName.trim();
        nodeRoles = Set.copyOf(Objects.requireNonNull(nodeRoles, "nodeRoles"));
        if (nodeRoles.isEmpty()) {
            throw new IllegalArgumentException("firefly.node.roles must contain at least one role");
        }
        plugins = Set.copyOf(Objects.requireNonNull(plugins, "plugins"));
        store = Objects.requireNonNull(store, "store");
        schedulerShards = Objects.requireNonNull(schedulerShards, "schedulerShards");
        schedulerCoordination = Objects.requireNonNull(schedulerCoordination, "schedulerCoordination");
        runtimeOptions = Objects.requireNonNull(runtimeOptions, "runtimeOptions");
        pluginConfiguration = Objects.requireNonNull(pluginConfiguration, "pluginConfiguration");
        adminApiToken = adminApiToken == null ? "" : adminApiToken.trim();
        if (nodeMode == ServerNodeMode.CLUSTER && !store.jdbcEnabled()) {
            throw new IllegalArgumentException("firefly.node.mode=cluster requires firefly.store.type=jdbc");
        }
        if (nodeMode == ServerNodeMode.CLUSTER
                && (runtimeOptions.jwtSecurity().usesDevelopmentCredentials()
                || runtimeOptions.adminSecurity().usesDevelopmentCredentials())) {
            throw new IllegalArgumentException(
                    "firefly.node.mode=cluster rejects bundled development security credentials; "
                            + "configure the JWT secret and Admin bootstrap password"
            );
        }
        if (adminHttpEnabled != nodeRoles.contains(ServerNodeRole.API)) {
            throw new IllegalArgumentException("admin HTTP must match the api node role");
        }
        if (nettyExecutorGatewayEnabled != nodeRoles.contains(ServerNodeRole.GATEWAY)) {
            throw new IllegalArgumentException("netty executor gateway must match the gateway node role");
        }
        if (demoEnabled && !nodeRoles.contains(ServerNodeRole.SCHEDULER)) {
            throw new IllegalArgumentException("demo jobs require the scheduler node role");
        }
        adminHttpHost = validateHost(adminHttpHost, "adminHttpHost");
        prometheusMetricsHost = validateHost(prometheusMetricsHost, "prometheusMetricsHost");
        validatePort(adminHttpPort, "adminHttpPort");
        validatePort(prometheusMetricsPort, "prometheusMetricsPort");
        validatePort(nettyExecutorGatewayPort, "nettyExecutorGatewayPort");
    }

    public ServerOptions(
            ServerNodeMode nodeMode,
            String nodeName,
            Set<ServerNodeRole> nodeRoles,
            Set<ServerPlugin> plugins,
            boolean demoEnabled,
            boolean adminHttpEnabled,
            String adminHttpHost,
            int adminHttpPort,
            boolean prometheusMetricsEnabled,
            String prometheusMetricsHost,
            int prometheusMetricsPort,
            boolean nettyExecutorGatewayEnabled,
            int nettyExecutorGatewayPort,
            ServerStoreOptions store
    ) {
        this(
                nodeMode, nodeName, nodeRoles, plugins, demoEnabled,
                adminHttpEnabled, adminHttpHost, adminHttpPort,
                prometheusMetricsEnabled, prometheusMetricsHost, prometheusMetricsPort,
                nettyExecutorGatewayEnabled, nettyExecutorGatewayPort, store, true
        );
    }

    public ServerOptions(
            ServerNodeMode nodeMode, String nodeName, Set<ServerNodeRole> nodeRoles, Set<ServerPlugin> plugins,
            boolean demoEnabled, boolean adminHttpEnabled, String adminHttpHost, int adminHttpPort,
            boolean prometheusMetricsEnabled, String prometheusMetricsHost, int prometheusMetricsPort,
            boolean nettyExecutorGatewayEnabled, int nettyExecutorGatewayPort, ServerStoreOptions store,
            boolean executorDefinitionAutoCreate
    ) {
        this(nodeMode, nodeName, nodeRoles, plugins, demoEnabled, adminHttpEnabled, adminHttpHost, adminHttpPort,
                prometheusMetricsEnabled, prometheusMetricsHost, prometheusMetricsPort,
                nettyExecutorGatewayEnabled, nettyExecutorGatewayPort, store,
                SchedulerShardConfig.defaults(), SchedulerCoordinationOptions.defaults(),
                executorDefinitionAutoCreate, "", ServerRuntimeOptions.defaults(),
                FireflyPluginConfiguration.empty());
    }

    public ServerOptions(
            ServerNodeMode nodeMode, String nodeName, Set<ServerNodeRole> nodeRoles, Set<ServerPlugin> plugins,
            boolean demoEnabled, boolean adminHttpEnabled, String adminHttpHost, int adminHttpPort,
            boolean prometheusMetricsEnabled, String prometheusMetricsHost, int prometheusMetricsPort,
            boolean nettyExecutorGatewayEnabled, int nettyExecutorGatewayPort, ServerStoreOptions store,
            boolean executorDefinitionAutoCreate, String adminApiToken
    ) {
        this(nodeMode, nodeName, nodeRoles, plugins, demoEnabled, adminHttpEnabled, adminHttpHost, adminHttpPort,
                prometheusMetricsEnabled, prometheusMetricsHost, prometheusMetricsPort,
                nettyExecutorGatewayEnabled, nettyExecutorGatewayPort, store,
                SchedulerShardConfig.defaults(), SchedulerCoordinationOptions.defaults(), executorDefinitionAutoCreate,
                adminApiToken, ServerRuntimeOptions.defaults(), FireflyPluginConfiguration.empty());
    }

    public static ServerOptions parse(String[] args) {
        return parse(args, System.getenv(), true);
    }

    static ServerOptions parse(String[] args, Map<String, String> env) {
        return parse(args, env, false);
    }

    private static ServerOptions parse(String[] args, Map<String, String> env, boolean useDefaultConfig) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        Map<String, String> flags = ServerFlagParser.parse(args);
        Map<String, String> config = ServerConfigFile.load(
                configPath(flags, env, useDefaultConfig),
                configProfile(flags, env)
        );
        ServerNodeMode nodeMode = nodeMode(flags, env, config);
        String nodeName = nodeName(flags, env, config, nodeMode);
        Set<ServerPlugin> plugins = pluginList(flags, env, config);
        boolean legacyAdminHttpEnabled = plugins.contains(ServerPlugin.ADMIN_HTTP)
                || booleanOption(flags, env, config, "firefly.admin-http.enabled", "FIREFLY_ADMIN_HTTP_ENABLED", false)
                || booleanOption(flags, env, config, "firefly.admin-web.enabled", "FIREFLY_ADMIN_WEB_ENABLED", false);
        boolean prometheusMetricsEnabled = plugins.contains(ServerPlugin.METRICS_PROMETHEUS)
                || booleanOption(
                        flags,
                        env,
                        config,
                        "firefly.metrics.prometheus.enabled",
                        "FIREFLY_METRICS_PROMETHEUS_ENABLED",
                        false
                );
        boolean legacyNettyExecutorGatewayEnabled = booleanOption(
                flags,
                env,
                config,
                "firefly.executor.gateway.netty.enabled",
                "FIREFLY_EXECUTOR_GATEWAY_NETTY_ENABLED",
                false
        );
        String rolesValue = stringOption(flags, env, config, "firefly.node.roles", "FIREFLY_NODE_ROLES", null);
        Set<ServerNodeRole> nodeRoles = nodeRoles(
                rolesValue,
                legacyAdminHttpEnabled,
                legacyNettyExecutorGatewayEnabled
        );
        validateRoleCompatibility(rolesValue, nodeRoles, legacyAdminHttpEnabled, legacyNettyExecutorGatewayEnabled);
        ServerStoreOptions store = storeOptions(flags, env, config);
        return new ServerOptions(
                nodeMode,
                nodeName,
                nodeRoles,
                plugins,
                booleanOption(flags, env, config, "firefly.demo.enabled", "FIREFLY_DEMO_ENABLED", false),
                nodeRoles.contains(ServerNodeRole.API),
                stringOption(flags, env, config, "firefly.admin-http.host", "FIREFLY_ADMIN_HTTP_HOST", "127.0.0.1"),
                intOption(
                        flags,
                        env,
                        config,
                        "firefly.admin-http.port",
                        "FIREFLY_ADMIN_HTTP_PORT",
                        intOption(
                                flags,
                                env,
                                config,
                                "firefly.admin-web.port",
                                "FIREFLY_ADMIN_WEB_PORT",
                                DEFAULT_ADMIN_HTTP_PORT
                        )
                ),
                prometheusMetricsEnabled,
                stringOption(
                        flags,
                        env,
                        config,
                        "firefly.metrics.prometheus.host",
                        "FIREFLY_METRICS_PROMETHEUS_HOST",
                        "127.0.0.1"
                ),
                intOption(
                        flags,
                        env,
                        config,
                        "firefly.metrics.prometheus.port",
                        "FIREFLY_METRICS_PROMETHEUS_PORT",
                        DEFAULT_PROMETHEUS_METRICS_PORT
                ),
                nodeRoles.contains(ServerNodeRole.GATEWAY),
                intOption(
                        flags,
                        env,
                        config,
                        "firefly.executor.gateway.netty.port",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_PORT",
                        DEFAULT_NETTY_EXECUTOR_GATEWAY_PORT
                ),
                store,
                new SchedulerShardConfig(intOption(
                        flags, env, config,
                        "firefly.scheduler.shard-count", "FIREFLY_SCHEDULER_SHARD_COUNT",
                        SchedulerShardConfig.DEFAULT_SHARD_COUNT
                )),
                schedulerCoordinationOptions(flags, env, config),
                booleanOption(
                        flags,
                        env,
                        config,
                        "firefly.executor.registration.auto-create-definition",
                        "FIREFLY_EXECUTOR_REGISTRATION_AUTO_CREATE_DEFINITION",
                        nodeMode == ServerNodeMode.STANDALONE
                ),
                stringOption(flags, env, config, "firefly.admin-http.api-token", "FIREFLY_ADMIN_HTTP_API_TOKEN", ""),
                runtimeOptions(flags, env, config),
                new FireflyPluginConfiguration(effectiveProperties(config, flags), env)
        );
    }

    private static Map<String, String> effectiveProperties(
            Map<String, String> config,
            Map<String, String> flags
    ) {
        java.util.HashMap<String, String> effective = new java.util.HashMap<>(config);
        effective.putAll(flags);
        return Map.copyOf(effective);
    }

    private static ServerRuntimeOptions runtimeOptions(
            Map<String, String> flags, Map<String, String> env, Map<String, String> config
    ) {
        DispatchOutboxOptions outbox = DispatchOutboxOptions.defaults();
        ExecutionMaintenanceOptions maintenance = ExecutionMaintenanceOptions.defaults();
        com.firefly.store.jdbc.JdbcClockOptions clock = com.firefly.store.jdbc.JdbcClockOptions.defaults();
        com.firefly.executor.netty.NettyExecutorGatewayOptions gateway =
                com.firefly.executor.netty.NettyExecutorGatewayOptions.defaults();
        boolean gatewayTlsEnabled = booleanOption(
                flags, env, config, "firefly.executor.gateway.netty.tls.enabled",
                "FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_ENABLED", false
        );
        com.firefly.executor.netty.NettyTlsOptions gatewayTls = new com.firefly.executor.netty.NettyTlsOptions(
                gatewayTlsEnabled,
                optionalPath(stringOption(flags, env, config,
                        "firefly.executor.gateway.netty.tls.certificate-chain",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_CERTIFICATE_CHAIN", "")),
                optionalPath(stringOption(flags, env, config,
                        "firefly.executor.gateway.netty.tls.private-key",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_PRIVATE_KEY", "")),
                stringOption(flags, env, config,
                        "firefly.executor.gateway.netty.tls.private-key-password",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_PRIVATE_KEY_PASSWORD", ""),
                optionalPath(stringOption(flags, env, config,
                        "firefly.executor.gateway.netty.tls.trust-certificates",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_TRUST_CERTIFICATES", "")),
                booleanOption(flags, env, config,
                        "firefly.executor.gateway.netty.tls.require-client-auth",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_REQUIRE_CLIENT_AUTH", false),
                false
        );
        return new ServerRuntimeOptions(
                new DispatchOutboxOptions(
                        durationOption(flags, env, config, "firefly.dispatch.outbox.poll-interval",
                                "FIREFLY_DISPATCH_OUTBOX_POLL_INTERVAL", outbox.pollInterval()),
                        intOption(flags, env, config, "firefly.dispatch.outbox.claim-batch-size",
                                "FIREFLY_DISPATCH_OUTBOX_CLAIM_BATCH_SIZE", outbox.claimBatchSize()),
                        durationOption(flags, env, config, "firefly.dispatch.outbox.claim-duration",
                                "FIREFLY_DISPATCH_OUTBOX_CLAIM_DURATION", outbox.claimDuration()),
                        durationOption(flags, env, config, "firefly.dispatch.outbox.remote-ack-timeout",
                                "FIREFLY_DISPATCH_OUTBOX_REMOTE_ACK_TIMEOUT", outbox.remoteAckTimeout()),
                        intOption(flags, env, config, "firefly.dispatch.outbox.max-attempts",
                                "FIREFLY_DISPATCH_OUTBOX_MAX_ATTEMPTS", outbox.maxAttempts()),
                        durationOption(flags, env, config, "firefly.dispatch.outbox.max-retry-backoff",
                                "FIREFLY_DISPATCH_OUTBOX_MAX_RETRY_BACKOFF", outbox.maxRetryBackoff())
                ),
                new ExecutionMaintenanceOptions(
                        durationOption(flags, env, config, "firefly.execution.maintenance.initial-delay",
                                "FIREFLY_EXECUTION_MAINTENANCE_INITIAL_DELAY", maintenance.initialDelay()),
                        durationOption(flags, env, config, "firefly.execution.maintenance.interval",
                                "FIREFLY_EXECUTION_MAINTENANCE_INTERVAL", maintenance.interval()),
                        durationOption(flags, env, config, "firefly.execution.maintenance.retention",
                                "FIREFLY_EXECUTION_MAINTENANCE_RETENTION", maintenance.retention()),
                        intOption(flags, env, config, "firefly.execution.maintenance.timeout-batch-size",
                                "FIREFLY_EXECUTION_MAINTENANCE_TIMEOUT_BATCH_SIZE", maintenance.timeoutBatchSize()),
                        intOption(flags, env, config, "firefly.execution.maintenance.cleanup-batch-size",
                                "FIREFLY_EXECUTION_MAINTENANCE_CLEANUP_BATCH_SIZE", maintenance.cleanupBatchSize())
                ),
                new com.firefly.store.jdbc.JdbcClockOptions(
                        durationOption(flags, env, config, "firefly.jdbc.clock.sync-interval",
                                "FIREFLY_JDBC_CLOCK_SYNC_INTERVAL", clock.syncInterval()),
                        durationOption(flags, env, config, "firefly.jdbc.clock.drift-warning-threshold",
                                "FIREFLY_JDBC_CLOCK_DRIFT_WARNING_THRESHOLD", clock.driftWarningThreshold())
                ),
                new com.firefly.executor.netty.NettyExecutorGatewayOptions(
                        intOption(flags, env, config, "firefly.executor.gateway.netty.result-queue-capacity",
                                "FIREFLY_EXECUTOR_GATEWAY_NETTY_RESULT_QUEUE_CAPACITY",
                                gateway.resultQueueCapacity()),
                        intOption(flags, env, config, "firefly.executor.gateway.netty.max-frame-length",
                                "FIREFLY_EXECUTOR_GATEWAY_NETTY_MAX_FRAME_LENGTH", gateway.maxFrameLength()),
                        gatewayTls,
                        durationOption(flags, env, config,
                                "firefly.executor.gateway.netty.tls.reload-interval",
                                "FIREFLY_EXECUTOR_GATEWAY_NETTY_TLS_RELOAD_INTERVAL",
                                gateway.tlsReloadInterval()),
                        stringOption(flags, env, config,
                                "firefly.executor.gateway.internal.host",
                                "FIREFLY_EXECUTOR_GATEWAY_INTERNAL_HOST", gateway.internalForwardHost()),
                        intOption(flags, env, config,
                                "firefly.executor.gateway.internal.port",
                                "FIREFLY_EXECUTOR_GATEWAY_INTERNAL_PORT", gateway.internalForwardPort()),
                        stringOption(flags, env, config,
                                "firefly.executor.gateway.internal.advertised-address",
                                "FIREFLY_EXECUTOR_GATEWAY_INTERNAL_ADVERTISED_ADDRESS",
                                gateway.advertisedInternalAddress()),
                        stringOption(flags, env, config,
                                "firefly.executor.gateway.internal.auth-token",
                                "FIREFLY_EXECUTOR_GATEWAY_INTERNAL_AUTH_TOKEN", gateway.internalAuthToken()),
                        durationOption(flags, env, config,
                                "firefly.executor.gateway.instance-location-refresh-interval",
                                "FIREFLY_EXECUTOR_GATEWAY_INSTANCE_LOCATION_REFRESH_INTERVAL",
                                gateway.instanceLocationRefreshInterval()),
                        durationOption(flags, env, config,
                                "firefly.executor.gateway.instance-location-lease",
                                "FIREFLY_EXECUTOR_GATEWAY_INSTANCE_LOCATION_LEASE",
                                gateway.instanceLocationLease())
                ),
                new AdminAuthorizationOptions(
                        stringOption(flags, env, config, "firefly.admin-http.reader-token",
                                "FIREFLY_ADMIN_HTTP_READER_TOKEN", ""),
                        stringOption(flags, env, config, "firefly.admin-http.operator-token",
                                "FIREFLY_ADMIN_HTTP_OPERATOR_TOKEN", ""),
                        stringOption(flags, env, config, "firefly.admin-http.admin-token",
                                "FIREFLY_ADMIN_HTTP_ADMIN_TOKEN", "")
                ),
                new com.firefly.engine.SchedulerEngineOptions(
                        intOption(flags, env, config, "firefly.scheduler.max-due-records-per-tick",
                                "FIREFLY_SCHEDULER_MAX_DUE_RECORDS_PER_TICK", 10_000),
                        durationOption(flags, env, config, "firefly.scheduler.max-idle-wakeup",
                                "FIREFLY_SCHEDULER_MAX_IDLE_WAKEUP", Duration.ofMillis(500))
                ),
                jwtSecurityOptions(flags, env, config),
                new AdminSecurityOptions(
                        stringOption(flags, env, config,
                                "firefly.security.admin.bootstrap-username",
                                "FIREFLY_SECURITY_ADMIN_BOOTSTRAP_USERNAME", ""),
                        stringOption(flags, env, config,
                                "firefly.security.admin.bootstrap-password",
                                "FIREFLY_SECURITY_ADMIN_BOOTSTRAP_PASSWORD", "")
                )
        );
    }

    private static JwtSecurityOptions jwtSecurityOptions(
            Map<String, String> flags, Map<String, String> env, Map<String, String> config
    ) {
        boolean enabled = booleanOption(flags, env, config,
                "firefly.security.jwt.enabled", "FIREFLY_SECURITY_JWT_ENABLED", false);
        if (!enabled) return JwtSecurityOptions.disabled();
        return new JwtSecurityOptions(
                enabled,
                stringOption(flags, env, config, "firefly.security.jwt.secret", "FIREFLY_SECURITY_JWT_SECRET", ""),
                stringOption(flags, env, config, "firefly.security.jwt.issuer", "FIREFLY_SECURITY_JWT_ISSUER", "firefly"),
                durationOption(flags, env, config, "firefly.security.jwt.access-token-ttl",
                        "FIREFLY_SECURITY_JWT_ACCESS_TOKEN_TTL", Duration.ofHours(1))
        );
    }

    private static Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static SchedulerCoordinationOptions schedulerCoordinationOptions(
            Map<String, String> flags, Map<String, String> env, Map<String, String> config
    ) {
        SchedulerCoordinationOptions defaults = SchedulerCoordinationOptions.defaults();
        return new SchedulerCoordinationOptions(
                durationOption(
                        flags, env, config,
                        "firefly.scheduler.coordination.reconcile-interval",
                        "FIREFLY_SCHEDULER_COORDINATION_RECONCILE_INTERVAL",
                        defaults.reconcileInterval()
                ),
                durationOption(
                        flags, env, config,
                        "firefly.scheduler.coordination.node-timeout",
                        "FIREFLY_SCHEDULER_COORDINATION_NODE_TIMEOUT",
                        defaults.nodeTimeout()
                ),
                durationOption(
                        flags, env, config,
                        "firefly.scheduler.coordination.lease-duration",
                        "FIREFLY_SCHEDULER_COORDINATION_LEASE_DURATION",
                        defaults.leaseDuration()
                )
        );
    }

    private static String configPath(Map<String, String> flags, Map<String, String> env, boolean useDefaultConfig) {
        String explicit = flags.getOrDefault("firefly.config", env.get("FIREFLY_CONFIG"));
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return useDefaultConfig ? defaultConfigPath() : null;
    }

    private static String configProfile(Map<String, String> flags, Map<String, String> env) {
        return flags.getOrDefault("firefly.config.profile", env.get("FIREFLY_CONFIG_PROFILE"));
    }

    private static String defaultConfigPath() {
        for (Path candidate : List.of(
                Path.of("config", "firefly-server.properties"),
                Path.of("..", "config", "firefly-server.properties"),
                Path.of("..", "..", "config", "firefly-server.properties")
        )) {
            if (Files.exists(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static ServerNodeMode nodeMode(Map<String, String> flags, Map<String, String> env, Map<String, String> config) {
        String value = stringOption(flags, env, config, "firefly.node.mode", "FIREFLY_NODE_MODE", "standalone");
        return ServerNodeMode.parse(value);
    }

    private static String nodeName(
            Map<String, String> flags,
            Map<String, String> env,
            Map<String, String> config,
            ServerNodeMode nodeMode
    ) {
        String value = stringOption(flags, env, config, "firefly.node.name", "FIREFLY_NODE_NAME", null);
        if (value != null && !value.isBlank()) {
            return value;
        }
        if (nodeMode == ServerNodeMode.STANDALONE) {
            return "firefly-standalone";
        }
        throw new IllegalArgumentException("firefly.node.name is required when firefly.node.mode=cluster");
    }

    private static Set<ServerNodeRole> nodeRoles(
            String rolesValue,
            boolean legacyAdminHttpEnabled,
            boolean legacyNettyExecutorGatewayEnabled
    ) {
        if (rolesValue != null) {
            return ServerNodeRole.parseList(rolesValue);
        }
        Set<ServerNodeRole> roles = new java.util.HashSet<>();
        roles.add(ServerNodeRole.SCHEDULER);
        if (legacyAdminHttpEnabled) {
            roles.add(ServerNodeRole.API);
        }
        if (legacyNettyExecutorGatewayEnabled) {
            roles.add(ServerNodeRole.GATEWAY);
        }
        return Set.copyOf(roles);
    }

    private static void validateRoleCompatibility(
            String rolesValue,
            Set<ServerNodeRole> nodeRoles,
            boolean legacyAdminHttpEnabled,
            boolean legacyNettyExecutorGatewayEnabled
    ) {
        if (rolesValue == null) {
            return;
        }
        if (legacyAdminHttpEnabled && !nodeRoles.contains(ServerNodeRole.API)) {
            throw new IllegalArgumentException("admin HTTP requires firefly.node.roles to include api");
        }
        if (legacyNettyExecutorGatewayEnabled && !nodeRoles.contains(ServerNodeRole.GATEWAY)) {
            throw new IllegalArgumentException("netty executor gateway requires firefly.node.roles to include gateway");
        }
    }

    private static Set<ServerPlugin> pluginList(Map<String, String> flags, Map<String, String> env, Map<String, String> config) {
        String value = stringOption(flags, env, config, "firefly.plugins", "FIREFLY_PLUGINS", null);
        return ServerPlugin.parseList(value);
    }

    private static ServerStoreOptions storeOptions(Map<String, String> flags, Map<String, String> env, Map<String, String> config) {
        String legacySchemaMode = booleanOption(
                flags,
                env,
                config,
                "firefly.jdbc.schema.initialize",
                "FIREFLY_JDBC_SCHEMA_INITIALIZE",
                true
        ) ? "initialize-if-empty" : "none";
        return new ServerStoreOptions(
                stringOption(flags, env, config, "firefly.store.type", "FIREFLY_STORE_TYPE", "memory"),
                stringOption(flags, env, config, "firefly.jdbc.url", "FIREFLY_JDBC_URL", null),
                stringOption(flags, env, config, "firefly.jdbc.username", "FIREFLY_JDBC_USERNAME", ""),
                stringOption(flags, env, config, "firefly.jdbc.password", "FIREFLY_JDBC_PASSWORD", ""),
                stringOption(flags, env, config, "firefly.jdbc.dialect", "FIREFLY_JDBC_DIALECT", "auto"),
                stringOption(flags, env, config, "firefly.jdbc.schema.mode", "FIREFLY_JDBC_SCHEMA_MODE", legacySchemaMode)
        );
    }

    private static boolean booleanOption(
            Map<String, String> flags,
            Map<String, String> env,
            Map<String, String> config,
            String flagName,
            String envName,
            boolean defaultValue
    ) {
        String value = stringOption(flags, env, config, flagName, envName, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int intOption(
            Map<String, String> flags,
            Map<String, String> env,
            Map<String, String> config,
            String flagName,
            String envName,
            int defaultValue
    ) {
        String value = stringOption(flags, env, config, flagName, envName, null);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static Duration durationOption(
            Map<String, String> flags,
            Map<String, String> env,
            Map<String, String> config,
            String flagName,
            String envName,
            Duration defaultValue
    ) {
        String value = stringOption(flags, env, config, flagName, envName, null);
        return value == null ? defaultValue : Duration.parse(value);
    }

    private static String stringOption(
            Map<String, String> flags,
            Map<String, String> env,
            Map<String, String> config,
            String flagName,
            String envName,
            String defaultValue
    ) {
        if (flags.containsKey(flagName)) {
            return flags.get(flagName);
        }
        if (env.containsKey(envName)) {
            return env.get(envName);
        }
        return config.getOrDefault(flagName, defaultValue);
    }

    private static void validatePort(int port, String name) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }

    private static String validateHost(String host, String name) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return host.trim();
    }
}
