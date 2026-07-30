package com.firefly.api.admin.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.firefly.cluster.FireflyNode;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.ScheduledJobRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Provides operational HTTP APIs without introducing a web framework into Firefly core.
 */
public final class AdminHttpPlugin implements FireflyPlugin {
    private final AdminHttpOptions options;
    private final AdminRequestReader requestReader;
    private final AdminHttpResponder responses = new AdminHttpResponder();
    private HttpServer server;
    private FireflyPluginContext context;
    private com.firefly.security.IntegrationKeyService integrationKeys;
    private AdminAuditService audit;
    private Instant startedAt;

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
        this.startedAt = this.context.clock().instant();
        this.integrationKeys = context.integrationKeyRepository()
                .map(repository -> new com.firefly.security.IntegrationKeyService(repository, context.clock()))
                .orElse(null);
        try {
            server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
            audit = new AdminAuditService(this.context);
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
            registerRoutes(
                    new AdminHttpRouter(server, dispatcher), authController, scheduleController,
                    jobController, executionController
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

    private void handleOverview(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "application/json; charset=utf-8",
                AdminHttpJson.overview(jobs(), onlineNodes(), executorInstances(), startedAt));
    }

    private void handleExecutors(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.executors(
                executorDefinitions(),
                executorInstances(),
                context.clock().instant(),
                options.heartbeatTimeout()
        ));
    }

    private void handleExecutorDefinitions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String definitionPrefix = "/api/executor-definitions/";
        if (path.startsWith("/api/executor-definitions/") && path.endsWith("/isolate")
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            isolateExecutor(exchange, path);
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())
                && path.startsWith(definitionPrefix)
                && path.length() > definitionPrefix.length()
                && path.indexOf('/', definitionPrefix.length()) < 0) {
            deleteExecutorDefinition(exchange, path.substring(definitionPrefix.length()));
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.executorDefinitions(executorDefinitions()));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            createExecutorDefinition(exchange);
            return;
        }
        respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
    }

    private void handleNodes(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/nodes/") && path.endsWith("/drain-status")
                && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String nodeId = URLDecoder.decode(
                    path.substring("/api/nodes/".length(), path.length() - "/drain-status".length()),
                    StandardCharsets.UTF_8
            );
            var status = context.nodeDrainStatusProvider()
                    .orElseThrow(() -> new IllegalStateException("nodeDrainStatusProvider is required"))
                    .status(nodeId);
            respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.nodeDrainStatus(status));
            return;
        }
        if (path.startsWith("/api/nodes/") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            updateNodeStatus(exchange, path);
            return;
        }
        respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.nodes(nodes()));
    }

    private void handleAudit(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var records = context.auditRepository().map(repository -> repository.listRecent(200)).orElse(List.of());
        respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.audit(records));
    }

    private List<ScheduledJobRecord> jobs() {
        return context.jobRepository().map(repository -> repository.list()).orElse(List.of());
    }

    private List<FireflyNode> nodes() {
        return context.nodeRegistry()
                .map(com.firefly.cluster.NodeRegistry::listAll)
                .orElse(List.of());
    }

    private List<FireflyNode> onlineNodes() {
        Instant now = context.clock().instant();
        return context.nodeRegistry()
                .map(registry -> registry.listOnline(now, options.heartbeatTimeout()))
                .orElse(List.of());
    }

    private void updateNodeStatus(HttpExchange exchange, String path) throws IOException {
        boolean draining = path.endsWith("/drain");
        boolean offline = path.endsWith("/offline");
        if (!draining && !offline) {
            respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"operation_not_found\"}");
            return;
        }
        String suffix = draining ? "/drain" : "/offline";
        String nodeId = URLDecoder.decode(
                path.substring("/api/nodes/".length(), path.length() - suffix.length()),
                StandardCharsets.UTF_8
        );
        var registry = context.nodeRegistry()
                .orElseThrow(() -> new IllegalStateException("nodeRegistry is required"));
        if (offline && registry.find(nodeId)
                .map(node -> node.status() == com.firefly.cluster.NodeStatus.OFFLINE)
                .orElse(false)) {
            respond(exchange, 200, "application/json; charset=utf-8",
                    "{\"status\":\"offline\",\"nodeId\":\"" + jsonEscape(nodeId) + "\"}");
            return;
        }
        if (offline && context.nodeDrainStatusProvider().isPresent()) {
            var status = context.nodeDrainStatusProvider().orElseThrow().status(nodeId);
            if (!status.readyForOffline()) {
                respond(exchange, 409, "application/json; charset=utf-8",
                        AdminHttpJson.nodeDrainStatus(status));
                return;
            }
        }
        String beforeStatus = registry.find(nodeId).map(node -> node.status().name()).orElse("");
        boolean updated = draining ? registry.markDraining(nodeId) : registry.markOffline(nodeId);
        if (!updated) {
            respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"node_not_found\"}");
            return;
        }
        exchange.setAttribute("firefly.audit.before", "{\"status\":\"" + beforeStatus + "\"}");
        exchange.setAttribute("firefly.audit.after", "{\"status\":\""
                + (draining ? "DRAINING" : "OFFLINE") + "\"}");
        respond(exchange, 202, "application/json; charset=utf-8",
                "{\"status\":\"" + (draining ? "draining" : "offline")
                        + "\",\"nodeId\":\"" + jsonEscape(nodeId) + "\"}");
    }

    private void isolateExecutor(HttpExchange exchange, String path) throws IOException {
        String executorName = URLDecoder.decode(
                path.substring("/api/executor-definitions/".length(), path.length() - "/isolate".length()),
                StandardCharsets.UTF_8
        );
        var catalog = context.schedulerCatalog()
                .orElseThrow(() -> new IllegalStateException("schedulerCatalog is required"));
        ExecutorDefinition current = catalog.findExecutor(executorName).orElse(null);
        if (current == null) {
            respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"executor_not_found\"}");
            return;
        }
        catalog.saveExecutor(new ExecutorDefinition(
                current.name(), current.description(), current.protocols(), current.metadata(), false
        ));
        exchange.setAttribute("firefly.audit.before", AdminHttpJson.executorDefinition(current));
        exchange.setAttribute("firefly.audit.after", AdminHttpJson.executorDefinition(
                new ExecutorDefinition(
                        current.name(), current.description(), current.protocols(), current.metadata(), false
                )
        ));
        com.firefly.executor.ExecutorIsolationResult isolation = context.executorIsolationDispatcher()
                .map(dispatcher -> dispatcher.isolate(executorName))
                .orElse(com.firefly.executor.ExecutorIsolationResult.local(0));
        String failedAddresses = isolation.failedGatewayAddresses().stream()
                .map(address -> "\"" + jsonEscape(address) + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        respond(exchange, 202, "application/json; charset=utf-8",
                "{\"status\":\"isolated\",\"executorName\":\"" + jsonEscape(executorName)
                        + "\",\"disconnectedInstances\":" + isolation.disconnectedInstances()
                        + ",\"contactedGateways\":" + isolation.contactedGateways()
                        + ",\"failedGateways\":" + isolation.failedGateways()
                        + ",\"failedGatewayAddresses\":[" + failedAddresses + "]}");
    }

    private void deleteExecutorDefinition(HttpExchange exchange, String encodedExecutorName) throws IOException {
        String executorName = URLDecoder.decode(encodedExecutorName, StandardCharsets.UTF_8);
        var catalog = context.schedulerCatalog()
                .orElseThrow(() -> new IllegalStateException("schedulerCatalog is required"));
        ExecutorDefinition current = catalog.findExecutor(executorName).orElse(null);
        if (current == null) {
            respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"executor_not_found\"}");
            return;
        }

        long jobCount = jobs().stream()
                .map(ScheduledJobRecord::definition)
                .filter(definition -> definition.remote()
                        && executorName.equals(definition.destination().executorName()))
                .count();
        if (jobCount > 0) {
            respond(exchange, 409, "application/json; charset=utf-8",
                    "{\"error\":\"executor_has_jobs\",\"jobCount\":" + jobCount + "}");
            return;
        }

        long jobGroupCount = catalog.listJobGroups().stream()
                .filter(group -> executorName.equals(group.executorName()))
                .count();
        if (jobGroupCount > 0) {
            respond(exchange, 409, "application/json; charset=utf-8",
                    "{\"error\":\"executor_has_job_groups\",\"jobGroupCount\":" + jobGroupCount + "}");
            return;
        }

        int onlineInstances = context.executorRegistry()
                .map(registry -> registry.listOnline(
                        executorName, context.clock().instant(), options.heartbeatTimeout()
                ).size())
                .orElse(0);
        if (onlineInstances > 0) {
            respond(exchange, 409, "application/json; charset=utf-8",
                    "{\"error\":\"executor_has_online_instances\",\"onlineInstances\":"
                            + onlineInstances + "}");
            return;
        }

        if (!catalog.deleteExecutor(executorName)) {
            respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"executor_not_found\"}");
            return;
        }
        exchange.setAttribute("firefly.audit.before", AdminHttpJson.executorDefinition(current));
        exchange.setAttribute("firefly.audit.after", "null");
        respond(exchange, 200, "application/json; charset=utf-8",
                "{\"status\":\"deleted\",\"executorName\":\"" + jsonEscape(executorName) + "\"}");
    }

    private void registerRoutes(
            AdminHttpRouter router,
            AdminAuthController authController,
            AdminScheduleController scheduleController,
            AdminJobController jobController,
            AdminExecutionController executionController
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
                .route("/api/overview", this::handleOverview)
                .route("/api/jobs", jobController::jobs)
                .route("/api/users", authController::users)
                .route("/api/executions", executionController::executions)
                .route("/api/outbox", executionController::outbox)
                .route("/api/executors", this::handleExecutors)
                .route("/api/executor-definitions", this::handleExecutorDefinitions)
                .route("/api/nodes", this::handleNodes)
                .route("/api/audit", this::handleAudit);
    }

    private List<ExecutorInstance> executorInstances() {
        return context.executorRegistry().map(registry -> registry.listAll()).orElse(List.of());
    }

    private List<ExecutorDefinition> executorDefinitions() {
        return context.schedulerCatalog().map(catalog -> catalog.listExecutors()).orElse(List.of());
    }

    private void createExecutorDefinition(HttpExchange exchange) throws IOException {
        Map<String, String> request = requestReader.object(exchange);
        String name = required(request, "name");
        Set<ExecutorProtocol> protocols = parseProtocols(request.getOrDefault("protocols", "TCP"));
        Map<String, String> metadata = new HashMap<>();
        request.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("metadata."))
                .forEach(entry -> metadata.put(entry.getKey().substring("metadata.".length()), entry.getValue()));
        ExecutorDefinition definition = ExecutorDefinition.builder()
                .name(name)
                .description(request.getOrDefault("description", ""))
                .protocols(protocols)
                .metadata(metadata)
                .enabled(Boolean.parseBoolean(request.getOrDefault("enabled", "true")))
                .build();
        context.schedulerCatalog()
                .orElseThrow(() -> new IllegalStateException("scheduler catalog is required"))
                .saveExecutor(definition);
        exchange.setAttribute("firefly.audit.after", AdminHttpJson.executorDefinition(definition));
        respond(exchange, 201, "application/json; charset=utf-8", AdminHttpJson.executorDefinition(definition));
    }

    private Set<ExecutorProtocol> parseProtocols(String value) {
        Set<ExecutorProtocol> protocols = new TreeSet<>(Comparator.comparing(Enum::name));
        for (String protocol : value.split(",")) {
            if (!protocol.isBlank()) {
                protocols.add(ExecutorProtocol.valueOf(protocol.trim().toUpperCase(java.util.Locale.ROOT)));
            }
        }
        if (protocols.isEmpty()) {
            throw new IllegalArgumentException("protocols must not be empty");
        }
        return Set.copyOf(protocols);
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        responses.ok(exchange, contentType, body);
    }

    private String jsonEscape(String value) {
        return responses.escape(value);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        responses.respond(exchange, status, contentType, body);
    }

    private String required(Map<String, String> request, String key) {
        String value = request.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

}
