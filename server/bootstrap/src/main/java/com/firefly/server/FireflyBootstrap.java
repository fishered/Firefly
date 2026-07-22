package com.firefly.server;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.firefly.catalog.SchedulerCatalog;
import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.NodeRegistry;
import com.firefly.cluster.NodeRole;
import com.firefly.cluster.NodeStatus;
import com.firefly.cluster.ShardManager;
import com.firefly.cluster.SchedulerShardConfig;
import com.firefly.engine.SchedulerEngine;
import com.firefly.engine.JobDispatcher;
import com.firefly.execution.ExecutionRepository;
import com.firefly.executor.netty.NettyExecutorGateway;
import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.plugin.FireflyPluginManager;
import com.firefly.api.admin.http.AdminHttpOptions;
import com.firefly.api.admin.http.AdminHttpPlugin;
import com.firefly.plugin.metrics.PrometheusMetricsOptions;
import com.firefly.plugin.metrics.PrometheusMetricsPlugin;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.store.JobRepository;
import com.firefly.store.DispatchType;
import com.firefly.store.jdbc.JdbcJobRepository;
import com.firefly.store.jdbc.JdbcNodeRegistry;
import com.firefly.store.jdbc.JdbcSchedulerCatalog;
import com.firefly.store.jdbc.JdbcShardManager;
import com.firefly.store.jdbc.JdbcExecutionRepository;
import com.firefly.store.jdbc.JdbcSchema;
import com.firefly.store.jdbc.JdbcSchemaOptions;
import com.firefly.metrics.SchedulerMetrics;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Owns server startup sequencing, similar to a lightweight bootstrap layer.
 */
public final class FireflyBootstrap implements AutoCloseable {
    private static final Logger log = Logger.getLogger(FireflyBootstrap.class.getName());

    private final SchedulerEngine engine;
    private final FireflyPluginManager plugins;
    private final NettyExecutorGateway executorGateway;
    private final SchedulerNodeCoordinator nodeCoordinator;
    private final DispatchOutboxWorker outboxWorker;
    private final ExecutionMaintenanceWorker executionMaintenanceWorker;
    private final NodeDrainMonitor nodeDrainMonitor;
    private final AutoCloseable runtimeClock;

    private FireflyBootstrap(
            SchedulerEngine engine,
            FireflyPluginManager plugins,
            NettyExecutorGateway executorGateway,
            SchedulerNodeCoordinator nodeCoordinator,
            DispatchOutboxWorker outboxWorker,
            ExecutionMaintenanceWorker executionMaintenanceWorker,
            NodeDrainMonitor nodeDrainMonitor,
            AutoCloseable runtimeClock
    ) {
        this.engine = engine;
        this.plugins = plugins;
        this.executorGateway = executorGateway;
        this.nodeCoordinator = nodeCoordinator;
        this.outboxWorker = outboxWorker;
        this.executionMaintenanceWorker = executionMaintenanceWorker;
        this.nodeDrainMonitor = nodeDrainMonitor;
        this.runtimeClock = runtimeClock;
    }

    public static FireflyBootstrap start(ServerOptions options) {
        if (options.runtimeOptions().jwtSecurity().usesDevelopmentCredentials()
                || options.runtimeOptions().adminSecurity().usesDevelopmentCredentials()) {
            log.warning("Firefly is using bundled local-development security credentials; "
                    + "replace them before any non-local deployment");
        }
        RuntimeAssembly assembly = schedulerAssembly(options);
        Injector injector = Guice.createInjector(assembly.module());
        JobRepository repository = injector.getInstance(JobRepository.class);
        JobHandlerRegistry handlerRegistry = injector.getInstance(JobHandlerRegistry.class);
        NodeRegistry nodeRegistry = injector.getInstance(NodeRegistry.class);
        SchedulerCatalog schedulerCatalog = injector.getInstance(SchedulerCatalog.class);
        SwitchableRemoteExecutionGateway remoteExecutionGateway = injector.getInstance(SwitchableRemoteExecutionGateway.class);
        SwitchableShardOwnership shardOwnership = injector.getInstance(SwitchableShardOwnership.class);
        ShardManager shardManager = injector.getInstance(ShardManager.class);
        ExecutionRepository executionRepository = injector.getInstance(ExecutionRepository.class);
        JobDispatcher jobDispatcher = injector.getInstance(JobDispatcher.class);
        SchedulerMetrics metrics = injector.getInstance(SchedulerMetrics.class);
        java.time.Clock runtimeClock = injector.getInstance(java.time.Clock.class);
        bootstrapAdminUser(assembly.adminUserRepository(), options.runtimeOptions().adminSecurity(), runtimeClock);
        java.util.function.BooleanSupplier acceptingNewWork = () -> nodeRegistry.find(options.nodeName())
                .map(node -> node.status() == NodeStatus.ONLINE)
                .orElse(false);

        registerNode(nodeRegistry, options, runtimeClock);
        SchedulerNodeCoordinator nodeCoordinator = new SchedulerNodeCoordinator(
                options.nodeName(),
                options.nodeRoles().contains(ServerNodeRole.SCHEDULER),
                nodeRegistry,
                shardManager,
                runtimeClock,
                options.schedulerShards().shardCount(),
                options.schedulerCoordination(),
                metrics
        );
        shardOwnership.install(nodeCoordinator);
        nodeCoordinator.start();
        ExecutionMaintenanceWorker executionMaintenanceWorker = null;
        if (options.nodeRoles().contains(ServerNodeRole.SCHEDULER)) {
            executionMaintenanceWorker = new ExecutionMaintenanceWorker(
                    executionRepository,
                    runtimeClock,
                    options.runtimeOptions().executionMaintenance(),
                    () -> nodeCoordinator.ownedShards().containsKey(0),
                    repository
            );
            executionMaintenanceWorker.start();
        }
        if (options.demoEnabled()) {
            DemoJobs.register(handlerRegistry, repository);
            log.info("Demo job registered: demo-print-every-5s");
        } else {
            log.info("Demo job disabled");
        }

        NettyExecutorGateway executorGateway = startExecutorGateway(
                options, schedulerCatalog, executionRepository, repository, metrics, runtimeClock,
                injector.getInstance(com.firefly.executor.ExecutorInstanceDirectory.class), acceptingNewWork
        );
        if (executorGateway != null) {
            remoteExecutionGateway.install(executorGateway::dispatch);
        }

        SchedulerEngine engine = null;
        DispatchOutboxWorker outboxWorker = null;
        Set<DispatchType> dispatchTypes = new HashSet<>();
        if (options.nodeRoles().contains(ServerNodeRole.SCHEDULER)) {
            dispatchTypes.add(DispatchType.LOCAL);
        }
        if (options.nodeRoles().contains(ServerNodeRole.GATEWAY)) {
            dispatchTypes.add(DispatchType.REMOTE);
        }
        if (!dispatchTypes.isEmpty()) {
            outboxWorker = new DispatchOutboxWorker(
                    options.nodeName(), repository, jobDispatcher, runtimeClock, dispatchTypes,
                    metrics, options.runtimeOptions().dispatchOutbox(), command ->
                    !command.definition().remote()
                            || executorGateway != null && executorGateway.hasRoute(
                            command.definition().destination().executorName()
                    )
            );
            outboxWorker.setClaimAdmission(acceptingNewWork);
            outboxWorker.start();
            log.info("Dispatch outbox worker enabled: types=" + dispatchTypes);
        }
        if (options.nodeRoles().contains(ServerNodeRole.SCHEDULER)) {
            engine = injector.getInstance(SchedulerEngine.class);
            engine.start();
            log.info("Scheduler role enabled");
        } else {
            log.info("Scheduler role disabled");
        }

        NodeDrainMonitor nodeDrainMonitor = new NodeDrainMonitor(
                options.nodeName(), nodeRegistry, shardManager, repository,
                executionRepository, executorGateway, runtimeClock
        );
        nodeDrainMonitor.start();

        FireflyPluginManager plugins = new FireflyPluginManager(configuredPlugins(options));
        FireflyPluginContext.Builder pluginContext = FireflyPluginContext.builder()
                .jobRepository(repository)
                .jobHandlerRegistry(handlerRegistry)
                .nodeRegistry(nodeRegistry)
                .schedulerCatalog(schedulerCatalog)
                .executionRepository(executionRepository)
                .schedulerShardCount(options.schedulerShards().shardCount())
                .schedulerMetrics(metrics)
                .auditRepository(injector.getInstance(com.firefly.audit.AuditRepository.class))
                .jobHistoryRepository(injector.getInstance(com.firefly.store.JobHistoryRepository.class))
                .adminUserRepository(assembly.adminUserRepository())
                .nodeDrainStatusProvider(nodeDrainMonitor);
        if (executorGateway != null) {
            pluginContext.remoteExecutorDispatcher(executorGateway::dispatch);
            pluginContext.executionCancellationDispatcher(executorGateway::cancel);
            pluginContext.executorIsolationDispatcher(executorGateway::isolateDetailed);
            pluginContext.executorRegistry(executorGateway.executorRegistry());
        }
        plugins.start(pluginContext.build());

        log.info("Firefly server started");
        return new FireflyBootstrap(
                engine, plugins, executorGateway, nodeCoordinator, outboxWorker, executionMaintenanceWorker,
                nodeDrainMonitor, assembly.closeable()
        );
    }

    public void await() throws InterruptedException {
        Thread.currentThread().join();
    }

    @Override
    public void close() {
        plugins.close();
        nodeDrainMonitor.close();
        if (engine != null) {
            engine.stop();
        }
        if (outboxWorker != null) {
            outboxWorker.close();
        }
        if (executionMaintenanceWorker != null) {
            executionMaintenanceWorker.close();
        }
        nodeCoordinator.close();
        if (executorGateway != null) {
            executorGateway.close();
        }
        try {
            runtimeClock.close();
        } catch (Exception e) {
            throw new IllegalStateException("failed to close runtime clock", e);
        }
    }

    private static RuntimeAssembly schedulerAssembly(ServerOptions options) {
        ServerStoreOptions store = options.store();
        int shardCount = options.schedulerShards().shardCount();
        if (!store.jdbcEnabled()) {
            log.info("Storage: memory");
            return new RuntimeAssembly(
                    new SchedulerModule(shardCount, options.runtimeOptions().schedulerEngine()),
                    () -> { },
                    new com.firefly.security.InMemoryAdminUserRepository()
            );
        }
        DataSource dataSource = new DriverManagerDataSource(store.jdbcUrl(), store.jdbcUsername(), store.jdbcPassword());
        JdbcSchemaOptions schemaOptions = JdbcSchemaOptions.of(store.jdbcDialect())
                .withSchedulerShardCount(shardCount);
        switch (store.jdbcSchemaMode()) {
            case "initialize-if-empty" -> {
                JdbcSchema.initializeIfEmpty(dataSource, schemaOptions);
                log.info("JDBC schema initialized if empty or validated: dialect=" + store.jdbcDialect());
            }
            case "initialize" -> {
                JdbcSchema.initialize(dataSource, schemaOptions);
                log.info("JDBC schema initialized: dialect=" + store.jdbcDialect());
            }
            case "validate" -> {
                JdbcSchema.validate(dataSource, schemaOptions);
                log.info("JDBC schema validated: dialect=" + store.jdbcDialect());
            }
            case "none" -> {
                JdbcSchema.validateClusterInvariant(dataSource, schemaOptions);
                log.info("JDBC schema migration skipped; cluster invariants validated");
            }
            default -> throw new IllegalStateException("unsupported jdbc schema mode: " + store.jdbcSchemaMode());
        }
        log.info("Storage: jdbc url=" + store.jdbcUrl());
        SchedulerMetrics metrics = new SchedulerMetrics();
        com.firefly.store.jdbc.JdbcDatabaseClock clock = new com.firefly.store.jdbc.JdbcDatabaseClock(
                dataSource, options.runtimeOptions().jdbcClock(), metrics
        );
        SchedulerModule module = new SchedulerModule(
                new JdbcJobRepository(dataSource, shardCount),
                new JdbcNodeRegistry(dataSource),
                new JdbcSchedulerCatalog(dataSource, shardCount),
                new JdbcShardManager(dataSource),
                new JdbcExecutionRepository(dataSource), shardCount, clock, metrics,
                options.runtimeOptions().schedulerEngine(),
                new com.firefly.store.jdbc.JdbcAuditRepository(dataSource),
                new com.firefly.store.jdbc.JdbcJobHistoryRepository(dataSource),
                new com.firefly.store.jdbc.JdbcExecutorInstanceDirectory(dataSource)
        );
        return new RuntimeAssembly(module, clock, new com.firefly.store.jdbc.JdbcAdminUserRepository(dataSource));
    }

    private static void bootstrapAdminUser(
            com.firefly.security.AdminUserRepository repository,
            AdminSecurityOptions options,
            java.time.Clock clock
    ) {
        if (!options.bootstrapEnabled()) return;
        String username = options.bootstrapUsername();
        if (repository.find(username).isPresent()) {
            log.info("Admin bootstrap account already exists: " + username);
            return;
        }
        Instant now = clock.instant();
        char[] password = options.bootstrapPassword().toCharArray();
        String passwordHash;
        try {
            passwordHash = new com.firefly.security.Pbkdf2PasswordHasher().hash(password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        boolean created = repository.create(new com.firefly.security.AdminUser(
                username, passwordHash, Set.of(com.firefly.security.FireflyRole.ADMIN), true, 0, now, now
        ));
        if (created) log.info("Admin bootstrap account created: " + username);
        else log.info("Admin bootstrap account was created concurrently: " + username);
    }

    private static void registerNode(NodeRegistry nodeRegistry, ServerOptions options, java.time.Clock clock) {
        Instant now = clock.instant();
        nodeRegistry.register(new FireflyNode(
                options.nodeName(),
                nodeRoles(options),
                now,
                now,
                NodeStatus.ONLINE,
                Map.of(
                        "mode", options.nodeMode().name().toLowerCase(),
                        "roles", options.nodeRoles().toString().toLowerCase()
                )
        ));
        log.info("Firefly node: " + options.nodeName()
                + " mode=" + options.nodeMode().name().toLowerCase()
                + " roles=" + options.nodeRoles());
    }

    private static Set<NodeRole> nodeRoles(ServerOptions options) {
        Set<NodeRole> roles = new HashSet<>();
        if (options.nodeRoles().contains(ServerNodeRole.SCHEDULER)) {
            roles.add(NodeRole.SCHEDULER);
        }
        if (options.nodeRoles().contains(ServerNodeRole.API)) {
            roles.add(NodeRole.API);
        }
        if (options.nodeRoles().contains(ServerNodeRole.GATEWAY)) {
            roles.add(NodeRole.GATEWAY);
        }
        return Set.copyOf(roles);
    }

    private static List<FireflyPlugin> configuredPlugins(ServerOptions options) {
        List<FireflyPlugin> plugins = new ArrayList<>();
        if (options.adminHttpEnabled()) {
            com.firefly.security.JwtService jwtService = jwtService(options, java.time.Clock.systemUTC());
            plugins.add(new AdminHttpPlugin(new AdminHttpOptions(
                    options.adminHttpHost(),
                    options.adminHttpPort(),
                    Duration.ofSeconds(30),
                    options.adminApiToken(), adminTokenRoles(options), jwtService,
                    options.runtimeOptions().jwtSecurity().clients()
            )));
            log.info("Admin HTTP: http://" + options.adminHttpHost() + ":" + options.adminHttpPort() + "/");
        }
        if (options.prometheusMetricsEnabled()) {
            plugins.add(new PrometheusMetricsPlugin(new PrometheusMetricsOptions(
                    options.prometheusMetricsHost(),
                    options.prometheusMetricsPort(),
                    "/metrics",
                    Duration.ofSeconds(30)
            )));
            log.info("Metrics: http://" + options.prometheusMetricsHost() + ":" + options.prometheusMetricsPort() + "/metrics");
        }
        return plugins;
    }

    private static java.util.Map<String, com.firefly.api.admin.http.AdminRole> adminTokenRoles(
            ServerOptions options
    ) {
        java.util.Map<String, com.firefly.api.admin.http.AdminRole> roles = new java.util.HashMap<>();
        AdminAuthorizationOptions configured = options.runtimeOptions().adminAuthorization();
        putToken(roles, configured.readerToken(), com.firefly.api.admin.http.AdminRole.READER);
        putToken(roles, configured.operatorToken(), com.firefly.api.admin.http.AdminRole.OPERATOR);
        putToken(roles, configured.adminToken(), com.firefly.api.admin.http.AdminRole.ADMIN);
        return roles;
    }

    private static void putToken(
            java.util.Map<String, com.firefly.api.admin.http.AdminRole> roles,
            String token,
            com.firefly.api.admin.http.AdminRole role
    ) {
        if (token != null && !token.isBlank()) {
            roles.merge(token, role, (left, right) -> left.ordinal() >= right.ordinal() ? left : right);
        }
    }

    private static NettyExecutorGateway startExecutorGateway(
            ServerOptions options,
            SchedulerCatalog schedulerCatalog,
            ExecutionRepository executionRepository,
            JobRepository jobRepository,
            SchedulerMetrics metrics,
            java.time.Clock runtimeClock,
            com.firefly.executor.ExecutorInstanceDirectory instanceDirectory,
            java.util.function.BooleanSupplier registrationAdmission
    ) {
        if (!options.nettyExecutorGatewayEnabled()) {
            log.info("Netty executor gateway disabled");
            return null;
        }
        NettyExecutorGateway gateway = new NettyExecutorGateway(
                options.nettyExecutorGatewayPort(),
                new com.firefly.executor.InMemoryExecutorRegistry(),
                new com.firefly.executor.netty.NettyExecutorConnectionRegistry(),
                runtimeClock,
                schedulerCatalog,
                options.executorDefinitionAutoCreate(),
                options.nodeName(),
                executionRepository,
                jobRepository::acknowledgeDispatch,
                options.executorAuthToken(),
                (executionId, timeout) -> jobRepository.scheduleExecutionRetry(
                        executionId, Instant.now(), timeout
                ),
                metrics,
                options.runtimeOptions().nettyGateway(),
                instanceDirectory
        );
        com.firefly.security.JwtService jwtService = jwtService(options, runtimeClock);
        if (jwtService != null) {
            gateway.setRegistrationAuthenticator((token, executorName) -> {
                com.firefly.security.FireflyPrincipal principal = jwtService.verify(token);
                return principal.allowsExecutor(executorName);
            });
        }
        gateway.setRegistrationAdmission(registrationAdmission);
        try {
            gateway.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting netty executor gateway", e);
        }
        log.info("Netty executor gateway: 127.0.0.1:" + options.nettyExecutorGatewayPort());
        return gateway;
    }

    private static com.firefly.security.JwtService jwtService(ServerOptions options, java.time.Clock clock) {
        JwtSecurityOptions security = options.runtimeOptions().jwtSecurity();
        if (!security.enabled()) return null;
        return new com.firefly.security.JwtService(
                security.secret(), security.issuer(), security.accessTokenTtl(), clock
        );
    }

    private record RuntimeAssembly(
            SchedulerModule module,
            AutoCloseable closeable,
            com.firefly.security.AdminUserRepository adminUserRepository
    ) {
    }
}
