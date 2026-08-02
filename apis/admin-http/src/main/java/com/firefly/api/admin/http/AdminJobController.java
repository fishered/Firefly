package com.firefly.api.admin.http;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutionRetryPolicy;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRetryScope;
import com.firefly.domain.ExecutorRoutingStrategy;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobDestination;
import com.firefly.domain.MisfirePolicy;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.ScheduledJobRecord;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AdminJobController {
    private final FireflyPluginContext context;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;
    private final AdminAuditService audit;

    AdminJobController(
            FireflyPluginContext context,
            AdminRequestReader requests,
            AdminHttpResponder responses,
            AdminAuditService audit
    ) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
    }

    void jobs(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/jobs/") && path.length() > "/api/jobs/".length()) {
            handleJob(exchange, path.substring("/api/jobs/".length()));
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 200, AdminHttpJson.jobs(listJobs()));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            createRemoteJob(exchange);
            return;
        }
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private void handleJob(HttpExchange exchange, String jobId) throws IOException {
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if (jobId.endsWith("/history") && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String actualJobId = URLDecoder.decode(
                    jobId.substring(0, jobId.length() - "/history".length()), StandardCharsets.UTF_8
            );
            var history = context.jobHistoryRepository()
                    .map(store -> store.listByJob(actualJobId, 100))
                    .orElse(List.of());
            respond(exchange, 200, AdminHttpJson.jobHistory(history));
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String actualJobId = URLDecoder.decode(jobId, StandardCharsets.UTF_8);
            var record = repository.find(actualJobId).orElse(null);
            if (record == null) {
                respond(exchange, 404, "{\"error\":\"job_not_found\"}");
                return;
            }
            respond(exchange, 200, AdminHttpJson.jobs(List.of(record)));
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
            respond(exchange, 202, "{\"status\":\"queued\",\"executionId\":\"" + executionId + "\"}");
            return;
        }
        if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            updateJob(exchange, jobId);
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            var before = repository.find(jobId).orElse(null);
            if (!repository.delete(jobId)) {
                respond(exchange, 404, "{\"error\":\"job_not_found\"}");
                return;
            }
            recordJobHistory(exchange, jobId, "DELETE", before, null);
            respond(exchange, 200, "{\"status\":\"deleted\"}");
            return;
        }
        if ("PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> request = requests.object(exchange);
            boolean enabled = requests.requiredBoolean(request, "enabled");
            var before = repository.find(jobId).orElse(null);
            if (!repository.setEnabled(jobId, enabled)) {
                respond(exchange, 404, "{\"error\":\"job_not_found\"}");
                return;
            }
            recordJobHistory(exchange, jobId, "SET_ENABLED", before, repository.find(jobId).orElse(null));
            respond(exchange, 200, "{\"status\":\"updated\",\"enabled\":" + enabled + "}");
            return;
        }
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private void updateJob(HttpExchange exchange, String jobId) throws IOException {
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        var current = repository.find(jobId)
                .orElseThrow(() -> new IllegalArgumentException("job not found: " + jobId));
        Map<String, String> request = requests.object(exchange);
        JobDefinition previous = current.definition();
        String executorName = request.getOrDefault(
                "executorName", previous.remote() ? previous.destination().executorName() : ""
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
                        Integer.parseInt(request.getOrDefault(
                                "retryMaxAttempts", Integer.toString(previous.retryPolicy().maxAttempts()))),
                        Duration.parse(request.getOrDefault(
                                "retryInitialDelay", previous.retryPolicy().initialDelay().toString())),
                        Double.parseDouble(request.getOrDefault(
                                "retryMultiplier", Double.toString(previous.retryPolicy().multiplier()))),
                        Duration.parse(request.getOrDefault(
                                "retryMaxDelay", previous.retryPolicy().maxDelay().toString())),
                        requests.booleanValue(request, "retryOnFailure", previous.retryPolicy().retryOnFailure()),
                        requests.booleanValue(request, "retryOnTimeout", previous.retryPolicy().retryOnTimeout())
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
                .enabled(requests.booleanValue(request, "enabled", previous.enabled()))
                .build();
        Instant now = context.clock().instant();
        Instant nextFireTime = updated.enabled()
                ? updated.schedule().nextAfter(now, updated.zoneId())
                : current.nextFireTime();
        repository.save(updated, nextFireTime);
        recordJobHistory(exchange, jobId, "UPDATE", current, repository.find(jobId).orElse(null));
        respond(exchange, 200, "{\"status\":\"updated\",\"id\":\""
                + responses.escape(jobId) + "\"}");
    }

    private void createRemoteJob(HttpExchange exchange) throws IOException {
        Map<String, String> request = requests.object(exchange);
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
            respond(exchange, 409, "{\"error\":\"job_already_exists\"}");
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
                        requests.booleanValue(request, "retryOnFailure", true),
                        requests.booleanValue(request, "retryOnTimeout", true)
                ))
                .parameters(parameters)
                .dispatchMode(enumValue(ExecutorDispatchMode.class,
                        request.getOrDefault("dispatchMode", "UNICAST")))
                .routingStrategy(enumValue(ExecutorRoutingStrategy.class,
                        request.getOrDefault("routingStrategy", "ROUND_ROBIN")))
                .completionPolicy(enumValue(ExecutorCompletionPolicy.class,
                        request.getOrDefault("completionPolicy", "ALL_SUCCESS")))
                .shardCount(Integer.parseInt(request.getOrDefault("shardCount", "1")))
                .routingKey(request.getOrDefault("routingKey", ""))
                .retryScope(enumValue(ExecutorRetryScope.class,
                        request.getOrDefault("retryScope", "FAILED_TARGETS_ONLY")))
                .enabled(requests.booleanValue(request, "enabled", true))
                .build();
        repository.save(job, job.schedule().nextAfter(context.clock().instant(), job.zoneId()));
        recordJobHistory(exchange, jobId, "CREATE", null, repository.find(jobId).orElse(null));
        respond(exchange, 201, "{\"status\":\"created\",\"id\":\"" + jobId + "\"}");
    }

    private List<ScheduledJobRecord> listJobs() {
        return context.jobRepository().map(repository -> repository.list()).orElse(List.of());
    }

    private void recordJobHistory(
            HttpExchange exchange,
            String jobId,
            String action,
            ScheduledJobRecord before,
            ScheduledJobRecord after
    ) {
        audit.recordJobHistory(exchange, jobId, action, before, after);
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
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
