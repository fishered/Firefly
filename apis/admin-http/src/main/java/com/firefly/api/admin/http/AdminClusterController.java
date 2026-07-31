package com.firefly.api.admin.http;

import com.firefly.cluster.FireflyNode;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorInstance;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.ScheduledJobRecord;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class AdminClusterController {
    private final AdminHttpOptions options;
    private final FireflyPluginContext context;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;
    private final Instant startedAt;

    AdminClusterController(
            AdminHttpOptions options,
            FireflyPluginContext context,
            AdminRequestReader requests,
            AdminHttpResponder responses,
            Instant startedAt
    ) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
        this.startedAt = java.util.Objects.requireNonNull(startedAt, "startedAt");
    }

    void overview(HttpExchange exchange) throws IOException {
        respond(exchange, 200,
                AdminHttpJson.overview(jobs(), onlineNodes(), executorInstances(), startedAt));
    }

    void executors(HttpExchange exchange) throws IOException {
        respond(exchange, 200, AdminHttpJson.executors(
                executorDefinitions(), executorInstances(), context.clock().instant(), options.heartbeatTimeout()
        ));
    }

    void executorDefinitions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String definitionPrefix = "/api/executor-definitions/";
        if (path.startsWith(definitionPrefix) && path.endsWith("/isolate")
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
            respond(exchange, 200, AdminHttpJson.executorDefinitions(executorDefinitions()));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            createExecutorDefinition(exchange);
            return;
        }
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    void nodes(HttpExchange exchange) throws IOException {
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
            respond(exchange, 200, AdminHttpJson.nodeDrainStatus(status));
            return;
        }
        if (path.startsWith("/api/nodes/") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            updateNodeStatus(exchange, path);
            return;
        }
        respond(exchange, 200, AdminHttpJson.nodes(nodes()));
    }

    private void updateNodeStatus(HttpExchange exchange, String path) throws IOException {
        boolean draining = path.endsWith("/drain");
        boolean offline = path.endsWith("/offline");
        if (!draining && !offline) {
            respond(exchange, 404, "{\"error\":\"operation_not_found\"}");
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
            respond(exchange, 200, "{\"status\":\"offline\",\"nodeId\":\""
                    + responses.escape(nodeId) + "\"}");
            return;
        }
        if (offline && context.nodeDrainStatusProvider().isPresent()) {
            var status = context.nodeDrainStatusProvider().orElseThrow().status(nodeId);
            if (!status.readyForOffline()) {
                respond(exchange, 409, AdminHttpJson.nodeDrainStatus(status));
                return;
            }
        }
        String beforeStatus = registry.find(nodeId).map(node -> node.status().name()).orElse("");
        boolean updated = draining ? registry.markDraining(nodeId) : registry.markOffline(nodeId);
        if (!updated) {
            respond(exchange, 404, "{\"error\":\"node_not_found\"}");
            return;
        }
        exchange.setAttribute("firefly.audit.before", "{\"status\":\"" + beforeStatus + "\"}");
        exchange.setAttribute("firefly.audit.after", "{\"status\":\""
                + (draining ? "DRAINING" : "OFFLINE") + "\"}");
        respond(exchange, 202, "{\"status\":\"" + (draining ? "draining" : "offline")
                + "\",\"nodeId\":\"" + responses.escape(nodeId) + "\"}");
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
            respond(exchange, 404, "{\"error\":\"executor_not_found\"}");
            return;
        }
        ExecutorDefinition isolated = new ExecutorDefinition(
                current.name(), current.description(), current.protocols(), current.metadata(), false
        );
        catalog.saveExecutor(isolated);
        exchange.setAttribute("firefly.audit.before", AdminHttpJson.executorDefinition(current));
        exchange.setAttribute("firefly.audit.after", AdminHttpJson.executorDefinition(isolated));
        com.firefly.executor.ExecutorIsolationResult isolation = context.executorIsolationDispatcher()
                .map(dispatcher -> dispatcher.isolate(executorName))
                .orElse(com.firefly.executor.ExecutorIsolationResult.local(0));
        String failedAddresses = isolation.failedGatewayAddresses().stream()
                .map(address -> "\"" + responses.escape(address) + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        respond(exchange, 202,
                "{\"status\":\"isolated\",\"executorName\":\"" + responses.escape(executorName)
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
            respond(exchange, 404, "{\"error\":\"executor_not_found\"}");
            return;
        }
        long jobCount = jobs().stream()
                .map(ScheduledJobRecord::definition)
                .filter(definition -> definition.remote()
                        && executorName.equals(definition.destination().executorName()))
                .count();
        if (jobCount > 0) {
            respond(exchange, 409, "{\"error\":\"executor_has_jobs\",\"jobCount\":" + jobCount + "}");
            return;
        }
        long jobGroupCount = catalog.listJobGroups().stream()
                .filter(group -> executorName.equals(group.executorName()))
                .count();
        if (jobGroupCount > 0) {
            respond(exchange, 409,
                    "{\"error\":\"executor_has_job_groups\",\"jobGroupCount\":" + jobGroupCount + "}");
            return;
        }
        int onlineInstances = context.executorRegistry()
                .map(registry -> registry.listOnline(
                        executorName, context.clock().instant(), options.heartbeatTimeout()
                ).size())
                .orElse(0);
        if (onlineInstances > 0) {
            respond(exchange, 409,
                    "{\"error\":\"executor_has_online_instances\",\"onlineInstances\":"
                            + onlineInstances + "}");
            return;
        }
        if (!catalog.deleteExecutor(executorName)) {
            respond(exchange, 404, "{\"error\":\"executor_not_found\"}");
            return;
        }
        exchange.setAttribute("firefly.audit.before", AdminHttpJson.executorDefinition(current));
        exchange.setAttribute("firefly.audit.after", "null");
        respond(exchange, 200, "{\"status\":\"deleted\",\"executorName\":\""
                + responses.escape(executorName) + "\"}");
    }

    private void createExecutorDefinition(HttpExchange exchange) throws IOException {
        Map<String, String> request = requests.object(exchange);
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
        respond(exchange, 201, AdminHttpJson.executorDefinition(definition));
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

    private List<ScheduledJobRecord> jobs() {
        return context.jobRepository().map(repository -> repository.list()).orElse(List.of());
    }

    private List<FireflyNode> nodes() {
        return context.nodeRegistry().map(com.firefly.cluster.NodeRegistry::listAll).orElse(List.of());
    }

    private List<FireflyNode> onlineNodes() {
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

    private String required(Map<String, String> request, String key) {
        String value = request.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        responses.respond(exchange, status, AdminHttpResponder.JSON, body);
    }
}
