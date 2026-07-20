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
    private static final java.util.logging.Logger auditLog = java.util.logging.Logger.getLogger(
            "com.firefly.audit.admin"
    );
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
            server.createContext("/api/audit", exchange -> handleSafely(exchange, this::handleAudit));
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
        respond(exchange, 200, "application/json; charset=utf-8",
                AdminHttpJson.overview(jobs(), onlineNodes(), executorInstances()));
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
        if (path.startsWith("/api/executor-definitions/") && path.endsWith("/isolate")
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            isolateExecutor(exchange, path);
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
            Map<String, String> request = AdminHttpJson.object(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8
            ));
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
        Map<String, String> request = AdminHttpJson.object(new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8
        ));
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
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        Map<String, String> request = bytes.length == 0
                ? Map.of()
                : AdminHttpJson.object(new String(bytes, StandardCharsets.UTF_8));
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

    private Map<String, String> readObject(HttpExchange exchange) throws IOException {
        return AdminHttpJson.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private List<String> ids(Map<String, String> request, String field) {
        String raw = required(request, field).trim();
        if (raw.startsWith("[") && raw.endsWith("]")) raw = raw.substring(1, raw.length() - 1);
        List<String> ids = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(value -> value.replaceAll("^\\\"|\\\"$", ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        if (ids.size() > 1000) throw new IllegalArgumentException(field + " exceeds batch limit 1000");
        return ids;
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
        } finally {
            auditMutation(exchange);
        }
    }

    private Authorization authorize(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if ("/api/health".equals(path)) return new Authorization(true, true);
        if (options.tokenRoles().isEmpty()) {
            exchange.setAttribute("firefly.admin.role", "UNRESTRICTED");
            return new Authorization(true, true);
        }
        String token = exchange.getRequestHeaders().getFirst("X-Firefly-Token");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if ((token == null || token.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring("Bearer ".length());
        }
        AdminRole role = roleForToken(token);
        exchange.setAttribute("firefly.admin.role", role == null ? "UNKNOWN" : role.name());
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
        if ("PATCH".equals(method) || "PUT".equals(method)
                || ("POST".equals(method) && path.endsWith("/trigger"))
                || ("POST".equals(method) && path.startsWith("/api/executions/") && path.endsWith("/cancel"))
                || ("POST".equals(method) && path.equals("/api/executions/batch-cancel"))
                || ("POST".equals(method) && path.equals("/api/outbox/batch-requeue"))
                || ("POST".equals(method) && path.startsWith("/api/outbox/") && path.endsWith("/requeue"))) {
            return AdminRole.OPERATOR;
        }
        return AdminRole.ADMIN;
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        exchange.setAttribute("firefly.response.status", status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void auditMutation(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(method) || "HEAD".equals(method)) return;
        Object status = exchange.getAttribute("firefly.response.status");
        Object role = exchange.getAttribute("firefly.admin.role");
        auditLog.info(() -> "admin_mutation method=" + method
                + " path=" + exchange.getRequestURI().getPath()
                + " role=" + (role == null ? "UNKNOWN" : role)
                + " status=" + (status == null ? 500 : status)
                + " remote=" + exchange.getRemoteAddress());
        context.auditRepository().ifPresent(repository -> {
            try {
                int responseStatus = status instanceof Integer value ? value : 500;
                repository.append(new com.firefly.audit.AuditRecord(
                        java.util.UUID.randomUUID().toString(), context.clock().instant(),
                        actor(exchange), role == null ? "UNKNOWN" : role.toString(),
                        method + " " + exchange.getRequestURI().getPath(),
                        auditResourceType(exchange), auditResourceId(exchange),
                        responseStatus >= 200 && responseStatus < 300 ? "SUCCESS" : "FAILURE",
                        attribute(exchange, "firefly.audit.before"),
                        attribute(exchange, "firefly.audit.after"),
                        "status=" + responseStatus + ", remote=" + exchange.getRemoteAddress()
                ));
            } catch (RuntimeException e) {
                auditLog.log(java.util.logging.Level.SEVERE, "failed to persist admin audit record", e);
            }
        });
    }

    private void recordJobHistory(
            HttpExchange exchange, String jobId, String action,
            ScheduledJobRecord before, ScheduledJobRecord after
    ) {
        String beforePayload = before == null ? "" : AdminHttpJson.jobPayload(before);
        String afterPayload = after == null ? "" : AdminHttpJson.jobPayload(after);
        exchange.setAttribute("firefly.audit.before", beforePayload);
        exchange.setAttribute("firefly.audit.after", afterPayload);
        context.jobHistoryRepository().ifPresent(repository -> repository.append(
                new com.firefly.store.JobHistoryRecord(
                        java.util.UUID.randomUUID().toString(), jobId, context.clock().millis(),
                        action, actor(exchange), beforePayload, afterPayload, context.clock().instant()
                )
        ));
    }

    private String actor(HttpExchange exchange) {
        String actor = exchange.getRequestHeaders().getFirst("X-Firefly-Actor");
        return actor == null || actor.isBlank() ? exchange.getRemoteAddress().toString() : actor;
    }

    private String auditResourceType(HttpExchange exchange) {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        return parts.length > 2 ? parts[2] : "admin";
    }

    private String auditResourceId(HttpExchange exchange) {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        return parts.length > 3 ? URLDecoder.decode(parts[3], StandardCharsets.UTF_8) : "";
    }

    private String attribute(HttpExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value == null ? "" : value.toString();
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
