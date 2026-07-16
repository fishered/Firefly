package com.firefly.api.admin.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.firefly.cluster.FireflyNode;
import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.domain.ExecutorRoutingStrategy;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobDestination;
import com.firefly.domain.ExecutionRetryPolicy;
import com.firefly.domain.MisfirePolicy;
import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.ScheduledJobRecord;
import com.firefly.execution.ExecutionRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
    private HttpServer server;
    private FireflyPluginContext context;

    public AdminHttpPlugin() {
        this(AdminHttpOptions.defaults());
    }

    public AdminHttpPlugin(AdminHttpOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return "admin-http";
    }

    @Override
    public void start(FireflyPluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        try {
            server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
            server.createContext("/", exchange -> handleSafely(exchange, this::handleIndex));
            server.createContext("/api/health", exchange -> handleSafely(exchange, this::handleHealth));
            server.createContext("/api/overview", exchange -> handleSafely(exchange, this::handleOverview));
            server.createContext("/api/jobs", exchange -> handleSafely(exchange, this::handleJobs));
            server.createContext("/api/executions", exchange -> handleSafely(exchange, this::handleExecutions));
            server.createContext("/api/outbox", exchange -> handleSafely(exchange, this::handleOutbox));
            server.createContext("/api/executors", exchange -> handleSafely(exchange, this::handleExecutors));
            server.createContext("/api/executor-definitions", exchange -> handleSafely(exchange, this::handleExecutorDefinitions));
            server.createContext("/api/nodes", exchange -> handleSafely(exchange, this::handleNodes));
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

    private void handleOverview(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.overview(jobs(), nodes(), executorInstances()));
    }

    private void handleJobs(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/jobs/") && path.length() > "/api/jobs/".length()) {
            updateOrDeleteJob(exchange, path.substring("/api/jobs/".length()));
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.jobs(jobs()));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            createRemoteJob(exchange);
            return;
        }
        respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
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

    private void updateOrDeleteJob(HttpExchange exchange, String jobId) throws IOException {
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if (jobId.endsWith("/trigger") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String actualJobId = jobId.substring(0, jobId.length() - "/trigger".length());
            var record = repository.find(actualJobId)
                    .orElseThrow(() -> new IllegalArgumentException("job not found: " + actualJobId));
            Instant now = context.clock().instant();
            String executionId = actualJobId + "@manual:" + java.util.UUID.randomUUID();
            repository.enqueueManual(new com.firefly.engine.ExecutionCommand(
                    executionId, record.definition(), now, now, "manual-api", 1L
            ));
            respond(exchange, 202, "application/json; charset=utf-8",
                    "{\"status\":\"queued\",\"executionId\":\"" + executionId + "\"}");
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (!repository.delete(jobId)) {
                respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"job_not_found\"}");
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8", "{\"status\":\"deleted\"}");
            return;
        }
        if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> request = AdminHttpJson.object(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8
            ));
            boolean enabled = Boolean.parseBoolean(required(request, "enabled"));
            if (!repository.setEnabled(jobId, enabled)) {
                respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"job_not_found\"}");
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8",
                    "{\"status\":\"updated\",\"enabled\":" + enabled + "}");
            return;
        }
        respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
    }

    private void handleExecutions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/executions/") && path.length() > "/api/executions/".length()) {
            String executionId = URLDecoder.decode(
                    path.substring("/api/executions/".length()),
                    StandardCharsets.UTF_8
            );
            var repository = context.executionRepository()
                    .orElseThrow(() -> new IllegalStateException("executionRepository is required"));
            ExecutionRecord execution = repository.findExecution(executionId).orElse(null);
            if (execution == null) {
                respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"execution_not_found\"}");
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8",
                    AdminHttpJson.executionDetail(execution, repository.listTargets(executionId)));
            return;
        }
        List<ExecutionRecord> executions = context.executionRepository()
                .map(repository -> repository.listRecent(100))
                .orElse(List.of());
        String json = executions.isEmpty()
                ? AdminHttpJson.executions(jobs(), context.clock().instant())
                : AdminHttpJson.executionHistory(executions);
        respond(exchange, 200, "application/json; charset=utf-8", json);
    }

    private void handleNodes(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.nodes(nodes()));
    }

    private void handleOutbox(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if ("/api/outbox/dead".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 200, "application/json; charset=utf-8",
                    AdminHttpJson.deadDispatches(repository.listDeadDispatches(100)));
            return;
        }
        if (path.startsWith("/api/outbox/") && path.endsWith("/requeue")
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String outboxId = URLDecoder.decode(
                    path.substring("/api/outbox/".length(), path.length() - "/requeue".length()),
                    StandardCharsets.UTF_8
            );
            if (!repository.requeueDeadDispatch(outboxId, context.clock().instant())) {
                respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"dead_outbox_not_found\"}");
                return;
            }
            respond(exchange, 202, "application/json; charset=utf-8",
                    "{\"status\":\"requeued\",\"outboxId\":\"" + jsonEscape(outboxId) + "\"}");
            return;
        }
        respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
    }

    private List<ScheduledJobRecord> jobs() {
        return context.jobRepository().map(repository -> repository.list()).orElse(List.of());
    }

    private List<FireflyNode> nodes() {
        Instant now = context.clock().instant();
        return context.nodeRegistry()
                .map(registry -> registry.listOnline(now, options.heartbeatTimeout()))
                .orElse(List.of());
    }

    private List<ExecutorInstance> executorInstances() {
        return context.executorRegistry().map(registry -> registry.listAll()).orElse(List.of());
    }

    private List<ExecutorDefinition> executorDefinitions() {
        return context.schedulerCatalog().map(catalog -> catalog.listExecutors()).orElse(List.of());
    }

    private void createRemoteJob(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> request = AdminHttpJson.object(body);
        String executorName = required(request, "executorName");
        String businessHandlerName = required(request, "handlerName");
        String jobId = required(request, "id");
        String cron = request.getOrDefault("cron", "*/5 * * * * *");
        ZoneId zoneId = ZoneId.of(request.getOrDefault("zoneId", "UTC"));
        ExecutorDefinition executorDefinition = context.schedulerCatalog()
                .flatMap(catalog -> catalog.findExecutor(executorName))
                .orElseThrow(() -> new IllegalArgumentException("unknown executor definition: " + executorName));
        if (!executorDefinition.enabled()) {
            throw new IllegalArgumentException("executor definition is disabled: " + executorName);
        }

        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));

        Map<String, String> parameters = new HashMap<>();
        request.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("param."))
                .forEach(entry -> parameters.put(entry.getKey().substring("param.".length()), entry.getValue()));

        JobDefinition job = JobDefinition.builder()
                .id(jobId)
                .name(request.getOrDefault("name", jobId))
                .handlerName(businessHandlerName)
                .destination(JobDestination.remote(executorName))
                .schedule(new CronSchedule(cron))
                .zoneId(zoneId)
                .misfirePolicy(MisfirePolicy.FIRE_ONCE)
                .misfireGrace(Duration.ofSeconds(5))
                .concurrencyPolicy(ConcurrencyPolicy.FORBID)
                .timeout(Duration.ofSeconds(30))
                .retryPolicy(new ExecutionRetryPolicy(
                        Integer.parseInt(request.getOrDefault("retryMaxAttempts", "1")),
                        Duration.parse(request.getOrDefault("retryInitialDelay", "PT1S")),
                        Double.parseDouble(request.getOrDefault("retryMultiplier", "2.0")),
                        Duration.parse(request.getOrDefault("retryMaxDelay", "PT30S")),
                        Boolean.parseBoolean(request.getOrDefault("retryOnFailure", "true")),
                        Boolean.parseBoolean(request.getOrDefault("retryOnTimeout", "true"))
                ))
                .parameters(parameters)
                .dispatchMode(enumValue(
                        ExecutorDispatchMode.class,
                        request.getOrDefault("dispatchMode", "UNICAST")
                ))
                .routingStrategy(enumValue(
                        ExecutorRoutingStrategy.class,
                        request.getOrDefault("routingStrategy", "ROUND_ROBIN")
                ))
                .completionPolicy(enumValue(
                        ExecutorCompletionPolicy.class,
                        request.getOrDefault("completionPolicy", "ALL_SUCCESS")
                ))
                .shardCount(Integer.parseInt(request.getOrDefault("shardCount", "1")))
                .routingKey(request.getOrDefault("routingKey", ""))
                .enabled(true)
                .build();
        repository.save(job, job.schedule().nextAfter(context.clock().instant(), job.zoneId()));
        respond(exchange, 201, "application/json; charset=utf-8", "{\"status\":\"created\",\"id\":\"" + jobId + "\"}");
    }

    private void createExecutorDefinition(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> request = AdminHttpJson.object(body);
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

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        respond(exchange, 200, contentType, body);
    }

    private void handleSafely(HttpExchange exchange, ExchangeHandler handler) throws IOException {
        try {
            Authorization authorization = authorize(exchange);
            if (!authorization.authenticated()) {
                respond(exchange, 401, "application/json; charset=utf-8", "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!authorization.allowed()) {
                respond(exchange, 403, "application/json; charset=utf-8", "{\"error\":\"forbidden\"}");
                return;
            }
            handler.handle(exchange);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, "application/json; charset=utf-8",
                    "{\"error\":\"bad_request\",\"message\":\"" + jsonEscape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            respond(exchange, 500, "application/json; charset=utf-8",
                    "{\"error\":\"internal_error\",\"message\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    private Authorization authorize(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if ("/api/health".equals(path)) return new Authorization(true, true);
        if (options.tokenRoles().isEmpty()) return new Authorization(true, true);
        String token = exchange.getRequestHeaders().getFirst("X-Firefly-Token");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if ((token == null || token.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring("Bearer ".length());
        }
        AdminRole role = roleForToken(token);
        if (role == null) return new Authorization(false, false);
        return new Authorization(true, role.allows(requiredRole(exchange)));
    }

    private AdminRole roleForToken(String provided) {
        if (provided == null) return null;
        byte[] candidate = provided.getBytes(StandardCharsets.UTF_8);
        return options.tokenRoles().entrySet().stream()
                .filter(entry -> java.security.MessageDigest.isEqual(
                        entry.getKey().getBytes(StandardCharsets.UTF_8), candidate
                ))
                .map(Map.Entry::getValue)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(null);
    }

    private AdminRole requiredRole(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) || "HEAD".equals(method)) return AdminRole.READER;
        if ("PATCH".equals(method)
                || ("POST".equals(method) && path.endsWith("/trigger"))
                || ("POST".equals(method) && path.startsWith("/api/outbox/") && path.endsWith("/requeue"))) {
            return AdminRole.OPERATOR;
        }
        return AdminRole.ADMIN;
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String required(Map<String, String> request, String key) {
        String value = request.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private record Authorization(boolean authenticated, boolean allowed) {
    }

}
