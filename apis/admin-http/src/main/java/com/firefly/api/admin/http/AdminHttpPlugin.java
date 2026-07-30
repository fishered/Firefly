package com.firefly.api.admin.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * Provides operational HTTP APIs without introducing a web framework into Firefly core.
 */
public final class AdminHttpPlugin implements FireflyPlugin {
    private final AdminHttpOptions options;
    private final AdminRequestReader requestReader;
    private final AdminHttpResponder responses = new AdminHttpResponder();
    private HttpServer server;
    private FireflyPluginContext context;

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
        this.context = Objects.requireNonNull(context, "context");
        var startedAt = this.context.clock().instant();
        var integrationKeys = context.integrationKeyRepository()
                .map(repository -> new com.firefly.security.IntegrationKeyService(repository, context.clock()))
                .orElse(null);
        try {
            server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
            var audit = new AdminAuditService(this.context);
            AdminHttpDispatcher dispatcher = new AdminHttpDispatcher(
                    requestReader,
                    new AdminAuthorizationService(options, this.context, integrationKeys),
                    audit,
                    responses
            );
            AdminAuthController authController = new AdminAuthController(
                    options, this.context, integrationKeys, requestReader, responses, audit
            );
            AdminScheduleController scheduleController = new AdminScheduleController(
                    this.context, requestReader, responses
            );
            AdminJobController jobController = new AdminJobController(
                    this.context, requestReader, responses, audit
            );
            AdminExecutionController executionController = new AdminExecutionController(
                    this.context, requestReader, responses
            );
            AdminClusterController clusterController = new AdminClusterController(
                    options, this.context, requestReader, responses, startedAt
            );
            registerRoutes(
                    new AdminHttpRouter(server, dispatcher), authController, scheduleController,
                    jobController, executionController, clusterController
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
    }

    private void handleIndex(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"admin_ui_is_external\"}");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, "application/json; charset=utf-8", "{\"status\":\"UP\",\"plugin\":\"admin-http\"}");
    }

    private void handlePlugins(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        java.util.List<com.firefly.plugin.FireflyPluginDescriptor> plugins = context.pluginStatusProvider()
                .map(com.firefly.plugin.PluginStatusProvider::plugins).orElse(java.util.List.of());
        respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.plugins(plugins));
    }

    private void handleAudit(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var records = context.auditRepository().map(repository -> repository.listRecent(200)).orElse(List.of());
        respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.audit(records));
    }

    private void registerRoutes(
            AdminHttpRouter router,
            AdminAuthController authController,
            AdminScheduleController scheduleController,
            AdminJobController jobController,
            AdminExecutionController executionController,
            AdminClusterController clusterController
    ) {
        router.route("/", this::handleIndex)
                .route("/api/health", this::handleHealth)
                .route("/api/auth/config", authController::config)
                .route("/api/auth/login", authController::login)
                .route("/api/auth/password", authController::passwordChange)
                .route("/api/integration-key", authController::integrationKey)
                .route("/api/plugins", this::handlePlugins)
                .route("/api/schedules/preview", scheduleController::preview)
                .route("/api/schedules/timezones", scheduleController::timezones)
                .route("/api/overview", clusterController::overview)
                .route("/api/jobs", jobController::jobs)
                .route("/api/users", authController::users)
                .route("/api/executions", executionController::executions)
                .route("/api/outbox", executionController::outbox)
                .route("/api/executors", clusterController::executors)
                .route("/api/executor-definitions", clusterController::executorDefinitions)
                .route("/api/nodes", clusterController::nodes)
                .route("/api/audit", this::handleAudit);
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        responses.ok(exchange, contentType, body);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        responses.respond(exchange, status, contentType, body);
    }

}
