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
import com.firefly.domain.ExecutorRetryScope;
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
            registerRoutes(new AdminHttpRouter(server, dispatcher), authController);
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

    private void handleSchedulePreview(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, String> request = requestReader.object(exchange);
        String expression = required(request, "cron");
        ZoneId zoneId = ZoneId.of(request.getOrDefault("zoneId", "UTC"));
        int count = Math.max(1, Math.min(20, Integer.parseInt(request.getOrDefault("count", "5"))));
        CronSchedule schedule = new CronSchedule(expression);
        Instant cursor = context.clock().instant();
        StringBuilder json = new StringBuilder("{\"cron\":\"").append(jsonEscape(expression))
                .append("\",\"zoneId\":\"").append(jsonEscape(zoneId.getId())).append("\",\"nextFireTimes\":[");
        for (int i = 0; i < count; i++) {
            cursor = schedule.nextAfter(cursor, zoneId);
            if (i > 0) json.append(',');
            json.append("{\"instant\":\"").append(cursor).append("\",\"local\":\"")
                    .append(cursor.atZone(zoneId).toLocalDateTime()).append("\"}");
        }
        respond(exchange, 200, "application/json; charset=utf-8", json.append("]}").toString());
    }

    private void handleTimezones(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String query = queryParameter(exchange, "query").trim().toLowerCase(java.util.Locale.ROOT);
        List<String> zones = ZoneId.getAvailableZoneIds().stream()
                .filter(zone -> timezoneScore(zone, query) < Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt((String zone) -> timezoneScore(zone, query))
                        .thenComparing(java.util.function.Function.identity()))
                .limit(100).toList();
        String body = zones.stream().map(zone -> "\"" + jsonEscape(zone) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{\"timezones\":[", "]}"));
        respond(exchange, 200, "application/json; charset=utf-8", body);
    }

    private int timezoneScore(String zone, String query) {
        if (query.isBlank()) {
            List<String> preferred = List.of(
                    "UTC", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Tokyo", "Asia/Singapore",
                    "Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles"
            );
            int index = preferred.indexOf(zone);
            return index < 0 ? 100 : index;
        }
        String candidate = zone.toLowerCase(java.util.Locale.ROOT);
        if (candidate.equals(query)) return 0;
        if (candidate.startsWith(query)) return 1;
        if (java.util.Arrays.stream(candidate.split("[/_-]")).anyMatch(part -> part.startsWith(query))) return 2;
        if (candidate.contains(query)) return 3;
        return fuzzySubsequence(normalizeTimezone(candidate), normalizeTimezone(query)) ? 4 : Integer.MAX_VALUE;
    }

    private String normalizeTimezone(String value) {
        return value.replace("/", "").replace("_", "").replace("-", "").replace(" ", "");
    }

    private boolean fuzzySubsequence(String candidate, String query) {
        int queryIndex = 0;
        for (int index = 0; index < candidate.length() && queryIndex < query.length(); index++) {
            if (candidate.charAt(index) == query.charAt(queryIndex)) queryIndex++;
        }
        return queryIndex == query.length();
    }

    private void handleOverview(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "application/json; charset=utf-8",
                AdminHttpJson.overview(jobs(), onlineNodes(), executorInstances(), startedAt));
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

    private void updateOrDeleteJob(HttpExchange exchange, String jobId) throws IOException {
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if (jobId.endsWith("/history") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String actualJobId = URLDecoder.decode(
                    jobId.substring(0, jobId.length() - "/history".length()), StandardCharsets.UTF_8
            );
            var history = context.jobHistoryRepository()
                    .map(store -> store.listByJob(actualJobId, 100))
                    .orElse(List.of());
            respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.jobHistory(history));
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String actualJobId = URLDecoder.decode(jobId, StandardCharsets.UTF_8);
            var record = repository.find(actualJobId).orElse(null);
            if (record == null) {
                respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"job_not_found\"}");
                return;
            }
            respond(exchange, 200, "application/json; charset=utf-8", AdminHttpJson.jobs(List.of(record)));
            return;
        }
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
        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            updateJob(exchange, jobId);
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            var before = repository.find(jobId).orElse(null);
            if (!repository.delete(jobId)) {
                respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"job_not_found\"}");
                return;
            }
            recordJobHistory(exchange, jobId, "DELETE", before, null);
            respond(exchange, 200, "application/json; charset=utf-8", "{\"status\":\"deleted\"}");
            return;
        }
        if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> request = requestReader.object(exchange);
            boolean enabled = Boolean.parseBoolean(required(request, "enabled"));
            var before = repository.find(jobId).orElse(null);
            if (!repository.setEnabled(jobId, enabled)) {
                respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"job_not_found\"}");
                return;
            }
            recordJobHistory(exchange, jobId, "SET_ENABLED", before, repository.find(jobId).orElse(null));
            respond(exchange, 200, "application/json; charset=utf-8",
                    "{\"status\":\"updated\",\"enabled\":" + enabled + "}");
            return;
        }
        respond(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"method_not_allowed\"}");
    }

    private void updateJob(HttpExchange exchange, String jobId) throws IOException {
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        var current = repository.find(jobId)
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId));
        Map<String, String> request = requestReader.object(exchange);
        JobDefinition previous = current.definition();
        String executorName = request.getOrDefault(
                "executorName",
                previous.remote() ? previous.destination().executorName() : ""
        );
        if (executorName.isBlank()) {
            throw new IllegalArgumentException("executorName is required for remote jobs");
        }
        ExecutorDefinition executorDefinition = context.schedulerCatalog()
                .flatMap(catalog -> catalog.findExecutor(executorName))
                .orElseThrow(() -> new IllegalArgumentException("unknown executor definition: " + executorName));
        if (!executorDefinition.enabled()) {
            throw new IllegalArgumentException("executor definition is disabled: " + executorName);
        }
        String handlerName = request.getOrDefault("handlerName", previous.businessHandlerName());
        ZoneId zoneId = ZoneId.of(request.getOrDefault("zoneId", previous.zoneId().getId()));
        String cron = request.getOrDefault("cron", previous.schedule().toString());
        Map<String, String> parameters = new HashMap<>(previous.parameters());
        request.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("param."))
                .forEach(entry -> parameters.put(entry.getKey().substring("param.".length()), entry.getValue()));
        parameters.put("executorName", executorName);
        parameters.put("handlerName", handlerName);
        JobDefinition updated = JobDefinition.builder()
                .id(previous.id())
                .groupId(request.getOrDefault("groupId", previous.groupId()))
                .name(request.getOrDefault("name", previous.name()))
                .handlerName(handlerName)
                .destination(JobDestination.remote(executorName))
                .schedule(new CronSchedule(cron))
                .zoneId(zoneId)
                .misfirePolicy(enumValue(MisfirePolicy.class, request.getOrDefault(
                        "misfirePolicy", previous.misfirePolicy().name())))
                .misfireGrace(Duration.parse(request.getOrDefault(
                        "misfireGrace", previous.misfireGrace().toString())))
                .concurrencyPolicy(enumValue(ConcurrencyPolicy.class, request.getOrDefault(
                        "concurrencyPolicy", previous.concurrencyPolicy().name())))
                .maxCatchUpCount(Integer.parseInt(request.getOrDefault(
                        "maxCatchUpCount", Integer.toString(previous.maxCatchUpCount()))))
                .timeout(Duration.parse(request.getOrDefault("timeout", previous.timeout().toString())))
                .parameters(parameters)
                .retryPolicy(new ExecutionRetryPolicy(
                        Integer.parseInt(request.getOrDefault("retryMaxAttempts", Integer.toString(previous.retryPolicy().maxAttempts()))),
                        Duration.parse(request.getOrDefault("retryInitialDelay", previous.retryPolicy().initialDelay().toString())),
                        Double.parseDouble(request.getOrDefault("retryMultiplier", Double.toString(previous.retryPolicy().multiplier()))),
                        Duration.parse(request.getOrDefault("retryMaxDelay", previous.retryPolicy().maxDelay().toString())),
                        Boolean.parseBoolean(request.getOrDefault("retryOnFailure", Boolean.toString(previous.retryPolicy().retryOnFailure()))),
                        Boolean.parseBoolean(request.getOrDefault("retryOnTimeout", Boolean.toString(previous.retryPolicy().retryOnTimeout())))
                ))
                .dispatchMode(enumValue(ExecutorDispatchMode.class, request.getOrDefault(
                        "dispatchMode", previous.dispatchMode().name())))
                .routingStrategy(enumValue(ExecutorRoutingStrategy.class, request.getOrDefault(
                        "routingStrategy", previous.routingStrategy().name())))
                .completionPolicy(enumValue(ExecutorCompletionPolicy.class, request.getOrDefault(
                        "completionPolicy", previous.completionPolicy().name())))
                .shardCount(Integer.parseInt(request.getOrDefault(
                        "shardCount", Integer.toString(previous.shardCount()))))
                .routingKey(request.getOrDefault("routingKey", previous.routingKey()))
                .retryScope(enumValue(ExecutorRetryScope.class, request.getOrDefault(
                        "retryScope", previous.retryScope().name())))
                .enabled(Boolean.parseBoolean(request.getOrDefault(
                        "enabled", Boolean.toString(previous.enabled()))))
                .build();
        Instant now = context.clock().instant();
        Instant nextFireTime = updated.enabled()
                ? updated.schedule().nextAfter(now, updated.zoneId())
                : current.nextFireTime();
        repository.save(updated, nextFireTime);
        recordJobHistory(exchange, jobId, "UPDATE", current, repository.find(jobId).orElse(null));
        respond(exchange, 200, "application/json; charset=utf-8", "{\"status\":\"updated\",\"id\":\""
                + jsonEscape(jobId) + "\"}");
    }

    private void handleExecutions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/executions/batch-cancel".equals(path)
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            batchCancelExecutions(exchange);
            return;
        }
        if (path.startsWith("/api/executions/root/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String rootExecutionId = URLDecoder.decode(
                    path.substring("/api/executions/root/".length()), StandardCharsets.UTF_8
            );
            var repository = context.executionRepository()
                    .orElseThrow(() -> new IllegalStateException("executionRepository is required"));
            respond(exchange, 200, "application/json; charset=utf-8",
                    AdminHttpJson.executionHistory(repository.listByRootExecutionId(rootExecutionId)));
            return;
        }
        if (path.startsWith("/api/executions/") && path.length() > "/api/executions/".length()) {
            if (path.endsWith("/cancel") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                cancelExecution(exchange, path);
                return;
            }
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

    private void cancelExecution(HttpExchange exchange, String path) throws IOException {
        String executionId = URLDecoder.decode(
                path.substring("/api/executions/".length(), path.length() - "/cancel".length()),
                StandardCharsets.UTF_8
        );
        var executions = context.executionRepository()
                .orElseThrow(() -> new IllegalStateException("executionRepository is required"));
        ExecutionRecord current = executions.findExecution(executionId).orElse(null);
        if (current == null) {
            respond(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"execution_not_found\"}");
            return;
        }
        if (current.status().terminal()) {
            respond(exchange, 409, "application/json; charset=utf-8", "{\"error\":\"execution_already_terminal\"}");
            return;
        }
        Map<String, String> request = requestReader.optionalObject(exchange);
        String reason = request.getOrDefault("reason", "cancelled by operator");
        Instant now = context.clock().instant();
        if (!executions.cancelExecution(executionId, now, reason)) {
            respond(exchange, 409, "application/json; charset=utf-8", "{\"error\":\"execution_not_cancellable\"}");
            return;
        }
        context.jobRepository().ifPresent(repository -> repository.cancelDispatch(executionId, now, reason));
        int notifiedTargets = context.executionCancellationDispatcher()
                .map(dispatcher -> dispatcher.cancel(executionId, reason))
                .orElse(0);
        respond(exchange, 202, "application/json; charset=utf-8",
                "{\"status\":\"cancelled\",\"executionId\":\"" + jsonEscape(executionId)
                        + "\",\"notifiedTargets\":" + notifiedTargets + "}");
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

    private void handleOutbox(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if ("/api/outbox/dead".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 200, "application/json; charset=utf-8",
                    AdminHttpJson.deadDispatches(repository.listDeadDispatches(100)));
            return;
        }
        if ("/api/outbox/batch-requeue".equals(path)
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> request = readObject(exchange);
            List<String> outboxIds = ids(request, "outboxIds");
            Instant now = context.clock().instant();
            int requeued = 0;
            StringBuilder items = new StringBuilder();
            for (String outboxId : outboxIds) {
                boolean accepted = repository.requeueDeadDispatch(outboxId, now);
                if (accepted) requeued++;
                if (!items.isEmpty()) items.append(',');
                items.append("{\"outboxId\":\"").append(jsonEscape(outboxId))
                        .append("\",\"status\":\"")
                        .append(accepted ? "REQUEUED" : "NOT_FOUND_OR_NOT_DEAD").append("\"}");
            }
            respond(exchange, 202, "application/json; charset=utf-8",
                    "{\"status\":\"requeued\",\"requested\":" + outboxIds.size()
                            + ",\"requeued\":" + requeued + ",\"items\":[" + items + "]}");
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

    private void batchCancelExecutions(HttpExchange exchange) throws IOException {
        Map<String, String> request = readObject(exchange);
        List<String> executionIds = ids(request, "executionIds");
        String reason = request.getOrDefault("reason", "cancelled by batch operator");
        int cancelled = 0;
        int notified = 0;
        StringBuilder items = new StringBuilder();
        for (String executionId : executionIds) {
            int sent = cancelOne(executionId, reason);
            if (sent >= 0) {
                cancelled++;
                notified += sent;
            }
            if (!items.isEmpty()) items.append(',');
            items.append("{\"executionId\":\"").append(jsonEscape(executionId))
                    .append("\",\"status\":\"").append(sent >= 0 ? "CANCELLED" : "SKIPPED")
                    .append("\",\"notifiedTargets\":").append(Math.max(0, sent)).append('}');
        }
        respond(exchange, 202, "application/json; charset=utf-8",
                "{\"status\":\"cancelled\",\"requested\":" + executionIds.size()
                        + ",\"cancelled\":" + cancelled + ",\"notifiedTargets\":" + notified
                        + ",\"items\":[" + items + "]}");
    }

    private int cancelOne(String executionId, String reason) {
        var executions = context.executionRepository()
                .orElseThrow(() -> new IllegalStateException("executionRepository is required"));
        ExecutionRecord current = executions.findExecution(executionId).orElse(null);
        if (current == null || current.status().terminal()) return -1;
        Instant now = context.clock().instant();
        if (!executions.cancelExecution(executionId, now, reason)) return -1;
        context.jobRepository().ifPresent(repository -> repository.cancelDispatch(executionId, now, reason));
        return context.executionCancellationDispatcher()
                .map(dispatcher -> dispatcher.cancel(executionId, reason))
                .orElse(0);
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

    private Map<String, String> readObject(HttpExchange exchange) throws IOException {
        return requestReader.object(exchange);
    }

    private void registerRoutes(AdminHttpRouter router, AdminAuthController authController) {
        router.route("/", this::handleIndex)
                .route("/api/health", this::handleHealth)
                .route("/api/auth/config", authController::config)
                .route("/api/auth/login", authController::login)
                .route("/api/auth/password", authController::passwordChange)
                .route("/api/integration-key", authController::integrationKey)
                .route("/api/plugins", this::handlePlugins)
                .route("/api/schedules/preview", this::handleSchedulePreview)
                .route("/api/schedules/timezones", this::handleTimezones)
                .route("/api/overview", this::handleOverview)
                .route("/api/jobs", this::handleJobs)
                .route("/api/users", authController::users)
                .route("/api/executions", this::handleExecutions)
                .route("/api/outbox", this::handleOutbox)
                .route("/api/executors", this::handleExecutors)
                .route("/api/executor-definitions", this::handleExecutorDefinitions)
                .route("/api/nodes", this::handleNodes)
                .route("/api/audit", this::handleAudit);
    }

    private List<String> ids(Map<String, String> request, String field) {
        return requestReader.ids(request, field);
    }

    private List<ExecutorInstance> executorInstances() {
        return context.executorRegistry().map(registry -> registry.listAll()).orElse(List.of());
    }

    private List<ExecutorDefinition> executorDefinitions() {
        return context.schedulerCatalog().map(catalog -> catalog.listExecutors()).orElse(List.of());
    }

    private void createRemoteJob(HttpExchange exchange) throws IOException {
        Map<String, String> request = requestReader.object(exchange);
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
        if (repository.find(jobId).isPresent()) {
            respond(exchange, 409, "application/json; charset=utf-8", "{\"error\":\"job_already_exists\"}");
            return;
        }

        Map<String, String> parameters = new HashMap<>();
        request.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("param."))
                .forEach(entry -> parameters.put(entry.getKey().substring("param.".length()), entry.getValue()));

        JobDefinition job = JobDefinition.builder()
                .id(jobId)
                .name(request.getOrDefault("name", jobId))
                .groupId(request.getOrDefault("groupId", "default"))
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
                .retryScope(enumValue(
                        ExecutorRetryScope.class,
                        request.getOrDefault("retryScope", "FAILED_TARGETS_ONLY")
                ))
                .enabled(Boolean.parseBoolean(request.getOrDefault("enabled", "true")))
                .build();
        repository.save(job, job.schedule().nextAfter(context.clock().instant(), job.zoneId()));
        recordJobHistory(exchange, jobId, "CREATE", null, repository.find(jobId).orElse(null));
        respond(exchange, 201, "application/json; charset=utf-8", "{\"status\":\"created\",\"id\":\"" + jobId + "\"}");
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

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        responses.ok(exchange, contentType, body);
    }

    private String queryParameter(HttpExchange exchange, String name) {
        return requestReader.queryParameter(exchange, name);
    }

    private String jsonEscape(String value) {
        return responses.escape(value);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        responses.respond(exchange, status, contentType, body);
    }

    private void recordJobHistory(
            HttpExchange exchange, String jobId, String action,
            ScheduledJobRecord before, ScheduledJobRecord after
    ) {
        audit.recordJobHistory(exchange, jobId, action, before, after);
    }

    private String required(Map<String, String> request, String key) {
        String value = request.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

}
