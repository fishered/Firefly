package com.firefly.api.admin.http;

import com.firefly.execution.ExecutionRecord;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.operations.ExecutionTimelineService;
import com.firefly.store.ScheduledJobRecord;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class AdminExecutionController {
    private final FireflyPluginContext context;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;

    AdminExecutionController(
            FireflyPluginContext context,
            AdminRequestReader requests,
            AdminHttpResponder responses
    ) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
    }

    void executions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/executions/batch-cancel".equals(path)
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            batchCancel(exchange);
            return;
        }
        if (path.startsWith("/api/executions/root/") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String rootExecutionId = URLDecoder.decode(
                    path.substring("/api/executions/root/".length()), StandardCharsets.UTF_8
            );
            var repository = executionRepository();
            respond(exchange, 200,
                    AdminHttpJson.executionHistory(repository.listByRootExecutionId(rootExecutionId)));
            return;
        }
        if (path.startsWith("/api/executions/") && path.endsWith("/timeline")
                && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String executionId = URLDecoder.decode(
                    path.substring("/api/executions/".length(), path.length() - "/timeline".length()),
                    StandardCharsets.UTF_8
            );
            var repository = executionRepository();
            if (repository.findExecution(executionId).isEmpty()) {
                respond(exchange, 404, "{\"error\":\"execution_not_found\"}");
                return;
            }
            respond(exchange, 200,
                    AdminHttpJson.executionTimeline(new ExecutionTimelineService(repository).timeline(executionId)));
            return;
        }
        if (path.startsWith("/api/executions/") && path.length() > "/api/executions/".length()) {
            if (path.endsWith("/cancel") && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                cancelExecution(exchange, path);
                return;
            }
            String executionId = URLDecoder.decode(
                    path.substring("/api/executions/".length()), StandardCharsets.UTF_8
            );
            var repository = executionRepository();
            ExecutionRecord execution = repository.findExecution(executionId).orElse(null);
            if (execution == null) {
                respond(exchange, 404, "{\"error\":\"execution_not_found\"}");
                return;
            }
            respond(exchange, 200,
                    AdminHttpJson.executionDetail(execution, repository.listTargets(executionId)));
            return;
        }
        List<ExecutionRecord> executions = context.executionRepository()
                .map(repository -> repository.listRecent(100))
                .orElse(List.of());
        String json = executions.isEmpty()
                ? AdminHttpJson.executions(jobs(), context.clock().instant())
                : AdminHttpJson.executionHistory(executions);
        respond(exchange, 200, json);
    }

    void outbox(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if ("/api/outbox/dead".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 200, AdminHttpJson.deadDispatches(repository.listDeadDispatches(100)));
            return;
        }
        if ("/api/outbox/batch-requeue".equals(path)
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            BatchRequeueRequest request = requests.typedObject(exchange, BatchRequeueRequest.class);
            List<String> outboxIds = request.outboxIds();
            Instant now = context.clock().instant();
            int requeued = 0;
            StringBuilder items = new StringBuilder();
            for (String outboxId : outboxIds) {
                boolean accepted = repository.requeueDeadDispatch(outboxId, now);
                if (accepted) requeued++;
                if (!items.isEmpty()) items.append(',');
                items.append("{\"outboxId\":\"").append(responses.escape(outboxId))
                        .append("\",\"status\":\"")
                        .append(accepted ? "REQUEUED" : "NOT_FOUND_OR_NOT_DEAD").append("\"}");
            }
            respond(exchange, 202,
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
                respond(exchange, 404, "{\"error\":\"dead_outbox_not_found\"}");
                return;
            }
            respond(exchange, 202, "{\"status\":\"requeued\",\"outboxId\":\""
                    + responses.escape(outboxId) + "\"}");
            return;
        }
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private void cancelExecution(HttpExchange exchange, String path) throws IOException {
        String executionId = URLDecoder.decode(
                path.substring("/api/executions/".length(), path.length() - "/cancel".length()),
                StandardCharsets.UTF_8
        );
        var executions = executionRepository();
        ExecutionRecord current = executions.findExecution(executionId).orElse(null);
        if (current == null) {
            respond(exchange, 404, "{\"error\":\"execution_not_found\"}");
            return;
        }
        if (current.status().terminal()) {
            respond(exchange, 409, "{\"error\":\"execution_already_terminal\"}");
            return;
        }
        Map<String, String> request = requests.optionalObject(exchange);
        String reason = request.getOrDefault("reason", "cancelled by operator");
        Instant now = context.clock().instant();
        if (!new com.firefly.execution.ExecutionLifecycleService(executions).cancel(executionId, now, reason)) {
            respond(exchange, 409, "{\"error\":\"execution_not_cancellable\"}");
            return;
        }
        int notifiedTargets = context.executionCancellationDispatcher()
                .map(dispatcher -> dispatcher.cancel(executionId, reason))
                .orElse(0);
        respond(exchange, 202, "{\"status\":\"cancelled\",\"executionId\":\""
                + responses.escape(executionId) + "\",\"notifiedTargets\":" + notifiedTargets + "}");
    }

    private void batchCancel(HttpExchange exchange) throws IOException {
        BatchCancelRequest request = requests.typedObject(exchange, BatchCancelRequest.class);
        List<String> executionIds = request.executionIds();
        String reason = request.reason();
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
            items.append("{\"executionId\":\"").append(responses.escape(executionId))
                    .append("\",\"status\":\"").append(sent >= 0 ? "CANCELLED" : "SKIPPED")
                    .append("\",\"notifiedTargets\":").append(Math.max(0, sent)).append('}');
        }
        respond(exchange, 202,
                "{\"status\":\"cancelled\",\"requested\":" + executionIds.size()
                        + ",\"cancelled\":" + cancelled + ",\"notifiedTargets\":" + notified
                        + ",\"items\":[" + items + "]}");
    }

    private int cancelOne(String executionId, String reason) {
        var executions = executionRepository();
        ExecutionRecord current = executions.findExecution(executionId).orElse(null);
        if (current == null || current.status().terminal()) return -1;
        Instant now = context.clock().instant();
        if (!new com.firefly.execution.ExecutionLifecycleService(executions).cancel(executionId, now, reason)) return -1;
        return context.executionCancellationDispatcher()
                .map(dispatcher -> dispatcher.cancel(executionId, reason))
                .orElse(0);
    }

    private com.firefly.execution.ExecutionRepository executionRepository() {
        return context.executionRepository()
                .orElseThrow(() -> new IllegalStateException("executionRepository is required"));
    }

    private List<ScheduledJobRecord> jobs() {
        return context.jobRepository().map(repository -> repository.list()).orElse(List.of());
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        responses.respond(exchange, status, AdminHttpResponder.JSON, body);
    }
}
