package com.firefly.api.admin.http;

import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionReplayPlan;
import com.firefly.execution.ExecutionReplayRequest;
import com.firefly.execution.ExecutionReplayService;
import com.firefly.execution.ReplayDefinitionSnapshot;
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
import com.firefly.trigger.TriggerInbox;
import com.firefly.trigger.EventTriggerService;
import com.firefly.trigger.BackfillRequest;
import com.firefly.trigger.BackfillCoordinator;
import com.firefly.trigger.BackfillOperation;
import com.firefly.trigger.BackfillOptions;
import com.firefly.trigger.BackfillPreview;
import com.firefly.schedule.CalendarDefinition;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class AdminExecutionController {
    private final FireflyPluginContext context;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;
    private final TriggerInbox triggerInbox;
    private final BackfillCoordinator backfills;
    private final EventIngressSecurity eventSecurity;

    AdminExecutionController(
            FireflyPluginContext context,
            AdminRequestReader requests,
            AdminHttpResponder responses,
            BackfillCoordinator backfills,
            EventIngressSecurity eventSecurity
    ) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
        this.triggerInbox = context.triggerInbox().orElseThrow(() -> new IllegalStateException("trigger inbox is required"));
        this.backfills = backfills;
        this.eventSecurity = java.util.Objects.requireNonNull(eventSecurity, "eventSecurity");
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
        if (path.startsWith("/api/executions/") && path.endsWith("/replay/preview")
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            replay(exchange, path, true);
            return;
        }
        if (path.startsWith("/api/executions/") && path.endsWith("/replay")
                && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            replay(exchange, path, false);
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
        var repository = context.executionRepository().orElse(null);
        String json;
        if (repository == null) {
            json = AdminHttpJson.executions(jobs(), context.clock().instant());
        } else {
            String statusValue = AdminPageSupport.text(requests, exchange, "status");
            com.firefly.execution.ExecutionStatus status = statusValue.isBlank() ? null
                    : com.firefly.execution.ExecutionStatus.valueOf(statusValue.toUpperCase(java.util.Locale.ROOT));
            String jobIdFilter = AdminPageSupport.text(requests, exchange, "jobId");
            Instant fromFilter = AdminPageSupport.instant(requests, exchange, "from");
            Instant toFilter = AdminPageSupport.instant(requests, exchange, "to");
            var cursor = AdminPageSupport.cursor(requests, exchange);
            var page = repository.query(new com.firefly.execution.ExecutionQuery(
                    jobIdFilter, status, fromFilter, toFilter, cursor,
                    AdminPageSupport.limit(requests, exchange, 50)
            ));
            String itemsJson = page.items().isEmpty() && cursor == null && jobIdFilter.isBlank()
                    && status == null && fromFilter == null && toFilter == null
                    ? AdminHttpJson.executions(jobs(), context.clock().instant())
                    : AdminHttpJson.executionHistory(page.items());
            json = AdminPageSupport.json(itemsJson, page);
        }
        respond(exchange, 200, json);
    }

    void triggers(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String rawPath = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        if ("/api/triggers/failed".equals(path) && "GET".equalsIgnoreCase(method)) {
            respond(exchange, 200, failedTriggersJson(triggerInbox.listFailed(queryLimit(exchange, 100))));
            return;
        }
        String failedPrefix = "/api/triggers/failed/";
        if (rawPath.startsWith(failedPrefix) && "POST".equalsIgnoreCase(method)) {
            String suffix = rawPath.substring(failedPrefix.length());
            int separator = suffix.lastIndexOf('/');
            if (separator <= 0) {
                respond(exchange, 404, "{\"error\":\"trigger_operation_not_found\"}");
                return;
            }
            String key = URLDecoder.decode(suffix.substring(0, separator), StandardCharsets.UTF_8);
            String action = suffix.substring(separator + 1);
            if ("replay".equals(action)) {
                var jobs = context.jobRepository().orElseThrow(
                        () -> new IllegalStateException("jobRepository is required"));
                var result = new EventTriggerService(triggerInbox, jobs, context.clock()).replay(key);
                context.schedulerMetrics().ifPresent(metrics -> {
                    if (result.accepted()) metrics.recordTriggerReplay();
                    else metrics.recordTriggerDuplicate();
                });
                respond(exchange, result.accepted() ? 202 : 409, triggerResultJson(result));
                return;
            }
            if ("acknowledge".equals(action)) {
                boolean acknowledged = triggerInbox.acknowledgeFailed(key, context.clock().instant());
                respond(exchange, acknowledged ? 202 : 409,
                        "{\"acknowledged\":" + acknowledged + "}");
                return;
            }
            respond(exchange, 404, "{\"error\":\"trigger_operation_not_found\"}");
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) { respond(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        String prefix = "/api/triggers/";
        if (!path.startsWith(prefix)) { respond(exchange, 400, "{\"error\":\"job_id_required\"}"); return; }
        String jobId = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        Map<String, String> request = requests.object(exchange, eventSecurity.maxRequestBodyBytes());
        String eventId = required(request, "eventId");
        String eventType = required(request, "eventType");
        String idempotencyKey = required(request, "idempotencyKey");
        String payload = request.getOrDefault("payload", "");
        authenticateTrigger(exchange, jobId, eventId, eventType, idempotencyKey, payload);
        var jobs = context.jobRepository().orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        EventTriggerService.TriggerResult result;
        try {
            result = new EventTriggerService(triggerInbox, jobs, context.clock()).accept(jobId,
                    eventId, eventType, idempotencyKey, payload);
        } catch (RuntimeException failure) {
            context.schedulerMetrics().ifPresent(com.firefly.metrics.SchedulerMetrics::recordTriggerFailure);
            throw failure;
        }
        context.schedulerMetrics().ifPresent(metrics -> {
            if (result.duplicate()) metrics.recordTriggerDuplicate();
            else if (result.accepted()) metrics.recordTriggerAccepted();
            else metrics.recordTriggerFailure();
        });
        respond(exchange, result.duplicate() ? 200 : 202, triggerResultJson(result));
    }

    private void authenticateTrigger(HttpExchange exchange, String jobId, String eventId,
                                     String eventType, String idempotencyKey, String payload) {
        var verifier = eventSecurity.verifier(context);
        if (verifier == null) {
            context.schedulerMetrics().ifPresent(com.firefly.metrics.SchedulerMetrics::recordTriggerSecurityRejection);
            throw new AdminHttpException(503, "trigger_signing_not_configured",
                    "firefly.trigger.hmac-secret must be configured");
        }
        String timestamp = requiredHeader(exchange, "X-Firefly-Timestamp");
        String nonce = requiredHeader(exchange, "X-Firefly-Nonce");
        String signature = requiredHeader(exchange, "X-Firefly-Signature");
        if (nonce.length() > 256) {
            context.schedulerMetrics().ifPresent(com.firefly.metrics.SchedulerMetrics::recordTriggerSecurityRejection);
            throw new AdminHttpException(400, "invalid_trigger_nonce", "nonce is too long");
        }
        Instant signedAt;
        try {
            signedAt = Instant.parse(timestamp);
        } catch (RuntimeException invalidTimestamp) {
            context.schedulerMetrics().ifPresent(com.firefly.metrics.SchedulerMetrics::recordTriggerSecurityRejection);
            throw new AdminHttpException(401, "invalid_trigger_signature", "invalid trigger timestamp");
        }
        if (!verifier.verify(jobId, eventId, eventType, idempotencyKey, payload, signedAt, nonce, signature)) {
            context.schedulerMetrics().ifPresent(com.firefly.metrics.SchedulerMetrics::recordTriggerSecurityRejection);
            throw new AdminHttpException(401, "invalid_trigger_signature", "invalid or expired trigger signature");
        }
        Instant now = context.clock().instant();
        if (!triggerInbox.reserveNonce(nonce, now.plus(eventSecurity.nonceTtl()), now)) {
            context.schedulerMetrics().ifPresent(com.firefly.metrics.SchedulerMetrics::recordTriggerSecurityRejection);
            throw new AdminHttpException(409, "trigger_replay_detected", "trigger nonce has already been used");
        }
    }

    private String requiredHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            context.schedulerMetrics().ifPresent(com.firefly.metrics.SchedulerMetrics::recordTriggerSecurityRejection);
            throw new AdminHttpException(401, "invalid_trigger_signature", "missing " + name + " header");
        }
        return value.trim();
    }

    private int queryLimit(HttpExchange exchange, int defaultValue) {
        String value = requests.queryParameter(exchange, "limit");
        if (value.isBlank()) return defaultValue;
        int limit = Integer.parseInt(value);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        return limit;
    }

    private String triggerResultJson(EventTriggerService.TriggerResult result) {
        return "{\"accepted\":" + result.accepted() + ",\"duplicate\":" + result.duplicate()
                + ",\"status\":\"" + responses.escape(result.status()) + "\"}";
    }

    private String failedTriggersJson(List<com.firefly.trigger.FailedEventTrigger> events) {
        String values = events.stream().map(failed -> {
                    var event = failed.event();
                    return "{\"eventId\":\""
                        + responses.escape(event.eventId()) + "\",\"jobId\":\""
                        + responses.escape(failed.jobId()) + "\",\"eventType\":\""
                        + responses.escape(event.eventType()) + "\",\"idempotencyKey\":\""
                        + responses.escape(event.idempotencyKey()) + "\",\"receivedAt\":\""
                        + event.receivedAt() + "\",\"failedAt\":\"" + event.processedAt()
                        + "\",\"failureReason\":\"" + responses.escape(failed.failureReason()) + "\"}";
                })
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"failedTriggers\":[" + values + "]}";
    }

    void backfills(HttpExchange exchange) throws IOException {
        BackfillCoordinator coordinator = requireBackfills();
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("/api/backfills/preview".equals(path) && "POST".equalsIgnoreCase(method)) {
            Map<String, String> request = requests.object(exchange);
            BackfillRequest backfill = backfillRequest(request, "preview-" + UUID.randomUUID());
            BackfillPreview preview = coordinator.preview(backfill, backfillOptions(request));
            respond(exchange, 200, backfillPreviewJson(preview));
            return;
        }
        if ("/api/backfills".equals(path)) {
            if ("GET".equalsIgnoreCase(method)) {
                String statusValue = AdminPageSupport.text(requests, exchange, "status");
                com.firefly.trigger.BackfillProgress.BackfillStatus status = statusValue.isBlank() ? null
                        : com.firefly.trigger.BackfillProgress.BackfillStatus.valueOf(
                        statusValue.toUpperCase(java.util.Locale.ROOT));
                var page = coordinator.query(new com.firefly.trigger.BackfillQuery(
                        AdminPageSupport.text(requests, exchange, "jobId"), status,
                        AdminPageSupport.instant(requests, exchange, "from"),
                        AdminPageSupport.instant(requests, exchange, "to"),
                        AdminPageSupport.cursor(requests, exchange),
                        AdminPageSupport.limit(requests, exchange, 50)
                ));
                respond(exchange, 200, AdminPageSupport.json(backfillListJson(page.items()), page));
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                Map<String, String> request = requests.object(exchange);
                String requestId = request.getOrDefault("requestId", UUID.randomUUID().toString());
                BackfillRequest backfill = backfillRequest(request, requestId);
                var progress = coordinator.start(backfill, backfillOptions(request));
                respond(exchange, 202, backfillProgressJson(coordinator.operation(progress.requestId())));
                return;
            }
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String prefix = "/api/backfills/";
        if (!path.startsWith(prefix)) {
            respond(exchange, 400, "{\"error\":\"backfill_id_required\"}");
            return;
        }
        String suffix = path.substring(prefix.length());
        int actionSeparator = suffix.lastIndexOf('/');
        String requestId = URLDecoder.decode(
                actionSeparator < 0 ? suffix : suffix.substring(0, actionSeparator), StandardCharsets.UTF_8
        );
        if (actionSeparator < 0 && "GET".equalsIgnoreCase(method)) {
            respond(exchange, 200, backfillDetailJson(
                    coordinator.operation(requestId), coordinator.items(requestId, 500)
            ));
            return;
        }
        if (actionSeparator >= 0 && "POST".equalsIgnoreCase(method)) {
            String action = suffix.substring(actionSeparator + 1);
            switch (action) {
                case "pause" -> coordinator.pause(requestId);
                case "resume" -> coordinator.resume(requestId);
                case "cancel" -> coordinator.cancel(requestId);
                case "promote" -> coordinator.promote(requestId);
                default -> {
                    respond(exchange, 404, "{\"error\":\"backfill_action_not_found\"}");
                    return;
                }
            }
            respond(exchange, 202, backfillProgressJson(coordinator.operation(requestId)));
            return;
        }
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private BackfillRequest backfillRequest(Map<String, String> request, String defaultRequestId) {
        String requestId = request.getOrDefault("requestId", defaultRequestId);
        return new BackfillRequest(requestId, required(request, "jobId"),
                Instant.parse(required(request, "fromInclusive")), Instant.parse(required(request, "toInclusive")),
                Integer.parseInt(request.getOrDefault("maxExecutions", "10000")),
                request.getOrDefault("rootExecutionId", requestId));
    }

    private BackfillOptions backfillOptions(Map<String, String> request) {
        Set<Instant> retryOnly = new HashSet<>();
        String encoded = request.getOrDefault("retryOnlyTimes", "");
        if (!encoded.isBlank()) {
            for (String item : encoded.split(",")) retryOnly.add(Instant.parse(item.trim()));
        }
        return new BackfillOptions(
                Integer.parseInt(request.getOrDefault("batchSize", "100")),
                Integer.parseInt(request.getOrDefault("rateLimitPerSecond", "0")),
                Integer.parseInt(request.getOrDefault("canaryPercent", "100")), retryOnly
        );
    }

    private String backfillPreviewJson(BackfillPreview preview) {
        String fireTimes = preview.fireTimes().stream()
                .map(time -> "\"" + responses.escape(time.toString()) + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"requestId\":\"" + responses.escape(preview.requestId())
                + "\",\"expanded\":" + preview.expanded()
                + ",\"estimatedDuration\":\"" + responses.escape(preview.estimatedDuration().toString())
                + "\",\"canaryExecutions\":" + preview.canaryExecutions()
                + ",\"fireTimes\":[" + fireTimes + "]}";
    }

    private String backfillListJson(List<BackfillOperation> operations) {
        return "{\"backfills\":[" + operations.stream().map(this::backfillProgressJson)
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    private String backfillProgressJson(BackfillOperation operation) {
        var progress = operation.progress();
        return "{\"requestId\":\"" + responses.escape(progress.requestId())
                + "\",\"jobId\":\"" + responses.escape(operation.request().jobId())
                + "\",\"rootExecutionId\":\"" + responses.escape(operation.request().rootExecutionId())
                + "\",\"fromInclusive\":\"" + operation.request().fromInclusive()
                + "\",\"toInclusive\":\"" + operation.request().toInclusive()
                + "\",\"status\":\"" + progress.status().name()
                + "\",\"expanded\":" + progress.expanded()
                + ",\"dispatched\":" + progress.dispatched()
                + ",\"failed\":" + progress.failed()
                + ",\"cursor\":" + progress.cursor()
                + ",\"remaining\":" + progress.remaining()
                + ",\"canary\":" + progress.canary()
                + ",\"canaryExecutions\":" + operation.canaryExecutions()
                + ",\"batchSize\":" + operation.options().batchSize()
                + ",\"rateLimitPerSecond\":" + operation.options().rateLimitPerSecond()
                + ",\"createdAt\":\"" + operation.createdAt()
                + "\",\"updatedAt\":\"" + operation.updatedAt() + "\"}";
    }

    private String backfillDetailJson(
            BackfillOperation operation, List<com.firefly.trigger.BackfillItem> items
    ) {
        String progress = backfillProgressJson(operation);
        String failures = items.stream()
                .filter(item -> item.status() == com.firefly.trigger.BackfillItem.BackfillItemStatus.FAILED)
                .map(item -> "{\"ordinal\":" + item.ordinal()
                        + ",\"fireTime\":\"" + responses.escape(item.fireTime().toString())
                        + "\",\"executionId\":\"" + responses.escape(item.executionId())
                        + "\",\"error\":\"" + responses.escape(item.error()) + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return progress.substring(0, progress.length() - 1) + ",\"failures\":[" + failures + "]}";
    }

    private BackfillCoordinator requireBackfills() {
        if (backfills == null) throw new IllegalStateException("backfill coordinator is required");
        return backfills;
    }

    private void replay(HttpExchange exchange, String path, boolean preview) throws IOException {
        String suffix = preview ? "/replay/preview" : "/replay";
        String executionId = URLDecoder.decode(
                path.substring("/api/executions/".length(), path.length() - suffix.length()),
                StandardCharsets.UTF_8
        );
        var executionRepository = executionRepository();
        ExecutionRecord source = executionRepository.findExecution(executionId).orElse(null);
        if (source == null) {
            respond(exchange, 404, "{\"error\":\"execution_not_found\"}");
            return;
        }
        var jobs = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        var current = jobs.find(source.jobId()).orElse(null);
        if (current == null) {
            respond(exchange, 409, "{\"error\":\"current_job_definition_not_found\"}");
            return;
        }
        Map<String, String> request = requests.optionalObject(exchange);
        boolean failedTargetsOnly = strictBoolean(request, "failedTargetsOnly", true);
        boolean confirmed = strictBoolean(request, "confirmed", false);
        List<String> targetIds = request.getOrDefault("failedTargetIds", "").isBlank()
                ? List.of()
                : java.util.Arrays.stream(request.get("failedTargetIds").split(","))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        var originalDefinition = jobs.findDispatch(executionId)
                .map(record -> record.command().definition()).orElse(current.definition());
        ReplayDefinitionSnapshot original = replaySnapshot(originalDefinition, originalDefinition, jobs);
        ReplayDefinitionSnapshot latest = replaySnapshot(originalDefinition, current.definition(), jobs);
        ExecutionReplayRequest replayRequest = new ExecutionReplayRequest(
                executionId, current.definition(), original, latest, preview, false,
                failedTargetsOnly, targetIds
        );
        ExecutionReplayService service = new ExecutionReplayService(executionRepository, context.clock());
        ExecutionReplayPlan plan = service.plan(replayRequest);
        if (preview) {
            respond(exchange, 200, replayPlanJson(plan));
            return;
        }
        if (plan.requiresConfirmation() && !confirmed) {
            respond(exchange, 409, "{\"error\":\"replay_confirmation_required\",\"plan\":"
                    + replayPlanJson(plan) + "}");
            return;
        }
        if (!service.submitIfAccepted(plan, confirmed, jobs::enqueueManual)) {
            respond(exchange, 409, "{\"error\":\"replay_not_accepted\"}");
            return;
        }
        respond(exchange, 202, replayPlanJson(plan));
    }

    private ReplayDefinitionSnapshot replaySnapshot(
            com.firefly.domain.JobDefinition original,
            com.firefly.domain.JobDefinition value,
            com.firefly.store.JobRepository jobs
    ) {
        long definitionRevision = original.equals(value) ? 1 : 2;
        long calendarRevision = original.calendarId().equals(value.calendarId())
                ? jobs.findCalendar(value.calendarId()).map(CalendarDefinition::version).orElse(0L)
                : 1L;
        long dependencyRevision = original.dependencies().equals(value.dependencies()) ? 0 : 1;
        return new ReplayDefinitionSnapshot(
                definitionRevision, calendarRevision, dependencyRevision, value.parameters()
        );
    }

    private String replayPlanJson(ExecutionReplayPlan plan) {
        String differences = plan.differences().stream()
                .map(value -> "\"" + responses.escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"sourceExecutionId\":\"" + responses.escape(plan.sourceExecutionId())
                + "\",\"sourceRootExecutionId\":\"" + responses.escape(plan.sourceRootExecutionId())
                + "\",\"replayExecutionId\":\"" + responses.escape(plan.replayExecutionId())
                + "\",\"dryRun\":" + plan.dryRun()
                + ",\"requiresConfirmation\":" + plan.requiresConfirmation()
                + ",\"failedTargetsOnly\":" + plan.failedTargetsOnly()
                + ",\"differences\":[" + differences + "]}";
    }

    private boolean strictBoolean(Map<String, String> request, String field, boolean defaultValue) {
        String value = request.get(field);
        if (value == null || value.isBlank()) return defaultValue;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(field + " must be true or false");
    }

    void batches(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { respond(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        String prefix = "/api/batches/";
        if (!exchange.getRequestURI().getPath().startsWith(prefix)) { respond(exchange, 400, "{\"error\":\"batch_id_required\"}"); return; }
        String root = URLDecoder.decode(exchange.getRequestURI().getPath().substring(prefix.length()), StandardCharsets.UTF_8);
        var batch = context.batchRepository().flatMap(repository -> repository.find(root)).orElse(null);
        if (batch == null) { respond(exchange, 404, "{\"error\":\"batch_not_found\"}"); return; }
        var progress = batch.progress();
        respond(exchange, 200, "{\"rootExecutionId\":\"" + responses.escape(batch.rootExecutionId()) + "\",\"jobId\":\"" + responses.escape(batch.jobId()) + "\",\"progress\":{\"totalShards\":" + progress.totalShards() + ",\"completedShards\":" + progress.completedShards() + ",\"failedShards\":" + progress.failedShards() + ",\"retriedShards\":" + progress.retriedShards() + ",\"inputRecords\":" + progress.inputRecords() + ",\"outputRecords\":" + progress.outputRecords() + ",\"percent\":" + progress.percent() + ",\"slaStatus\":\"" + progress.slaStatus().name() + "\"}}");
    }

    void calendars(HttpExchange exchange) throws IOException {
        var jobs = context.jobRepository().orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            StringBuilder body = new StringBuilder("{\"calendars\":[");
            boolean first = true;
            for (CalendarDefinition c : jobs.listCalendars()) {
                if (!first) body.append(','); first = false;
                body.append("{\"id\":\"").append(responses.escape(c.id())).append("\",\"version\":").append(c.version())
                        .append(",\"zoneId\":\"").append(responses.escape(c.zoneId().getId())).append("\",\"workingDays\":\"")
                        .append(c.workingDays().stream().map(Enum::name).sorted().reduce((a,b)->a+","+b).orElse(""))
                        .append("\",\"holidays\":\"").append(c.holidays().stream().map(LocalDate::toString).sorted().reduce((a,b)->a+","+b).orElse(""))
                        .append("\",\"extraWorkingDays\":\"").append(c.extraWorkingDays().stream().map(LocalDate::toString).sorted().reduce((a,b)->a+","+b).orElse(""))
                        .append("\"}");
            }
            respond(exchange, 200, body.append("]}").toString()); return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { respond(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        Map<String,String> request = requests.object(exchange);
        String[] days = request.getOrDefault("workingDays", "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY").split(",");
        EnumSet<DayOfWeek> working = EnumSet.noneOf(DayOfWeek.class);
        for (String day : days) working.add(DayOfWeek.valueOf(day.trim().toUpperCase(java.util.Locale.ROOT)));
        Set<LocalDate> holidays = parseDates(request.get("holidays"));
        Set<LocalDate> extra = parseDates(request.get("extraWorkingDays"));
        CalendarDefinition calendar = new CalendarDefinition(required(request,"id"), Long.parseLong(request.getOrDefault("version","1")),
                ZoneId.of(request.getOrDefault("zoneId","Asia/Shanghai")), working, holidays, extra);
        jobs.saveCalendar(calendar); respond(exchange, 201, "{\"status\":\"created\",\"id\":\"" + responses.escape(calendar.id()) + "\"}");
    }

    private Set<LocalDate> parseDates(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<LocalDate> dates = new HashSet<>(); for (String item : value.split(",")) dates.add(LocalDate.parse(item.trim())); return Set.copyOf(dates);
    }

    private String required(Map<String, String> request, String name) {
        String value = request.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required field: " + name); return value;
    }

    void outbox(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        var repository = context.jobRepository()
                .orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        if ("/api/outbox/dead".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String typeValue = AdminPageSupport.text(requests, exchange, "dispatchType");
            com.firefly.store.DispatchType dispatchType = typeValue.isBlank() ? null
                    : com.firefly.store.DispatchType.valueOf(typeValue.toUpperCase(java.util.Locale.ROOT));
            var page = repository.queryDeadDispatches(new com.firefly.store.DispatchOutboxQuery(
                    AdminPageSupport.text(requests, exchange, "jobId"), dispatchType,
                    AdminPageSupport.instant(requests, exchange, "from"),
                    AdminPageSupport.instant(requests, exchange, "to"),
                    AdminPageSupport.cursor(requests, exchange),
                    AdminPageSupport.limit(requests, exchange, 50)
            ));
            respond(exchange, 200, AdminPageSupport.json(AdminHttpJson.deadDispatches(page.items()), page));
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
