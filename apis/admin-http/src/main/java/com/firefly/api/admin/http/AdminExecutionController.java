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

    AdminExecutionController(
            FireflyPluginContext context,
            AdminRequestReader requests,
            AdminHttpResponder responses,
            BackfillCoordinator backfills
    ) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
        this.triggerInbox = context.triggerInbox().orElseThrow(() -> new IllegalStateException("trigger inbox is required"));
        this.backfills = backfills;
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
        List<ExecutionRecord> executions = context.executionRepository()
                .map(repository -> repository.listRecent(100))
                .orElse(List.of());
        String json = executions.isEmpty()
                ? AdminHttpJson.executions(jobs(), context.clock().instant())
                : AdminHttpJson.executionHistory(executions);
        respond(exchange, 200, json);
    }

    void triggers(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { respond(exchange, 405, "{\"error\":\"method_not_allowed\"}"); return; }
        String prefix = "/api/triggers/";
        if (!exchange.getRequestURI().getPath().startsWith(prefix)) { respond(exchange, 400, "{\"error\":\"job_id_required\"}"); return; }
        String jobId = URLDecoder.decode(exchange.getRequestURI().getPath().substring(prefix.length()), StandardCharsets.UTF_8);
        Map<String, String> request = requests.object(exchange);
        var jobs = context.jobRepository().orElseThrow(() -> new IllegalStateException("jobRepository is required"));
        var result = new EventTriggerService(triggerInbox, jobs, context.clock()).accept(jobId,
                required(request, "eventId"), required(request, "eventType"), required(request, "idempotencyKey"), request.getOrDefault("payload", ""));
        respond(exchange, result.duplicate() ? 200 : 202, "{\"accepted\":" + result.accepted() + ",\"duplicate\":" + result.duplicate() + ",\"status\":\"" + responses.escape(result.status()) + "\"}");
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
                respond(exchange, 200, backfillListJson(coordinator.list(100)));
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
            respond(exchange, 200, backfillProgressJson(coordinator.operation(requestId)));
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
