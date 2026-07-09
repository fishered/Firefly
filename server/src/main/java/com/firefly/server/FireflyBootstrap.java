package com.firefly.server;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.NodeRegistry;
import com.firefly.cluster.NodeRole;
import com.firefly.cluster.NodeStatus;
import com.firefly.engine.SchedulerEngine;
import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.plugin.FireflyPluginManager;
import com.firefly.plugin.admin.AdminWebOptions;
import com.firefly.plugin.admin.AdminWebPlugin;
import com.firefly.plugin.metrics.PrometheusMetricsOptions;
import com.firefly.plugin.metrics.PrometheusMetricsPlugin;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.store.JobRepository;

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

    private FireflyBootstrap(SchedulerEngine engine, FireflyPluginManager plugins) {
        this.engine = engine;
        this.plugins = plugins;
    }

    public static FireflyBootstrap start(ServerOptions options) {
        Injector injector = Guice.createInjector(new SchedulerModule());
        JobRepository repository = injector.getInstance(JobRepository.class);
        JobHandlerRegistry handlerRegistry = injector.getInstance(JobHandlerRegistry.class);
        NodeRegistry nodeRegistry = injector.getInstance(NodeRegistry.class);

        registerNode(nodeRegistry, options);
        if (options.demoEnabled()) {
            DemoJobs.register(handlerRegistry, repository);
            log.info("Demo job registered: demo-print-every-5s");
        } else {
            log.info("Demo job disabled");
        }

        SchedulerEngine engine = injector.getInstance(SchedulerEngine.class);
        engine.start();

        FireflyPluginManager plugins = new FireflyPluginManager(configuredPlugins(options));
        plugins.start(FireflyPluginContext.builder()
                .jobRepository(repository)
                .nodeRegistry(nodeRegistry)
                .build());

        log.info("Firefly server started");
        return new FireflyBootstrap(engine, plugins);
    }

    public void await() throws InterruptedException {
        Thread.currentThread().join();
    }

    @Override
    public void close() {
        plugins.close();
        engine.stop();
    }

    private static void registerNode(NodeRegistry nodeRegistry, ServerOptions options) {
        Instant now = Instant.now();
        nodeRegistry.register(new FireflyNode(
                "firefly-server-node",
                nodeRoles(options),
                now,
                now,
                NodeStatus.ONLINE,
                Map.of("mode", "server")
        ));
    }

    private static Set<NodeRole> nodeRoles(ServerOptions options) {
        Set<NodeRole> roles = new HashSet<>();
        roles.add(NodeRole.SCHEDULER);
        if (options.adminWebEnabled()) {
            roles.add(NodeRole.API);
        }
        return Set.copyOf(roles);
    }

    private static List<FireflyPlugin> configuredPlugins(ServerOptions options) {
        List<FireflyPlugin> plugins = new ArrayList<>();
        if (options.adminWebEnabled()) {
            plugins.add(new AdminWebPlugin(new AdminWebOptions(
                    "127.0.0.1",
                    options.adminWebPort(),
                    Duration.ofSeconds(30)
            )));
            log.info("Admin Web: http://127.0.0.1:" + options.adminWebPort() + "/");
        }
        if (options.prometheusMetricsEnabled()) {
            plugins.add(new PrometheusMetricsPlugin(new PrometheusMetricsOptions(
                    "127.0.0.1",
                    options.prometheusMetricsPort(),
                    "/metrics",
                    Duration.ofSeconds(30)
            )));
            log.info("Metrics: http://127.0.0.1:" + options.prometheusMetricsPort() + "/metrics");
        }
        return plugins;
    }
}
