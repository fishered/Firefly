package com.firefly.api.admin.http;

import com.sun.net.httpserver.HttpServer;
import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Provides operational HTTP APIs without introducing a web framework into Firefly core.
 */
public final class AdminHttpPlugin implements FireflyPlugin {
    private final AdminHttpOptions options;
    private final AdminRequestReader requestReader;
    private final AdminHttpResponder responses = new AdminHttpResponder();
    private HttpServer server;
    private ExecutorService executor;

    public AdminHttpPlugin() {
        this(AdminHttpOptions.defaults());
    }

    public AdminHttpPlugin(AdminHttpOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.requestReader = new AdminRequestReader(options.requestLimits());
    }

    @Override
    public String id() {
        return "admin-http";
    }

    @Override
    public String displayName() {
        return "Admin HTTP API";
    }

    @Override
    public String description() {
        return "Administrative JSON API and authentication boundary";
    }

    @Override
    public void start(FireflyPluginContext context) {
        Objects.requireNonNull(context, "context");
        var startedAt = context.clock().instant();
        var integrationKeys = context.integrationKeyRepository()
                .map(repository -> new com.firefly.security.IntegrationKeyService(repository, context.clock()))
                .orElse(null);
        try {
            server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
            executor = new java.util.concurrent.ThreadPoolExecutor(
                    32, 32, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(256),
                    runnable -> new Thread(runnable, "firefly-admin-http"),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
            );
            server.setExecutor(executor);
            var audit = new AdminAuditService(context);
            AdminHttpDispatcher dispatcher = new AdminHttpDispatcher(
                    requestReader,
                    new AdminAuthorizationService(options, context, integrationKeys),
                    audit,
                    responses
            );
            AdminAuthController authController = new AdminAuthController(
                    options, context, integrationKeys, requestReader, responses, audit
            );
            AdminScheduleController scheduleController = new AdminScheduleController(
                    context, requestReader, responses
            );
            AdminJobController jobController = new AdminJobController(
                    context, requestReader, responses, audit
            );
            AdminExecutionController executionController = new AdminExecutionController(
                    context, requestReader, responses
            );
            AdminClusterController clusterController = new AdminClusterController(
                    options, context, requestReader, responses, startedAt
            );
            AdminRouteRegistration.register(
                    new AdminHttpRouter(server, dispatcher),
                    new AdminSystemController(context, responses),
                    authController, scheduleController, jobController, executionController, clusterController
            );
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start Firefly admin HTTP API", e);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) executor.shutdownNow();
    }

}
