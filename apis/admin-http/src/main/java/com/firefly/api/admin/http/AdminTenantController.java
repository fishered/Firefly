package com.firefly.api.admin.http;

import com.firefly.governance.TenantDefinition;
import com.firefly.governance.TenantCapacityBaseline;
import com.firefly.governance.TenantCapacityStore;
import com.firefly.governance.TenantQuotaStore;
import com.firefly.governance.TenantQuotaPlanner;
import com.firefly.governance.TenantQuotaPreview;
import com.firefly.governance.TenantQuotaProposal;
import com.firefly.governance.TenantUsage;
import com.firefly.plugin.FireflyPluginContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Dynamic tenant quota management and authoritative usage inspection. */
final class AdminTenantController {
    private static final String BASE = "/api/tenants";
    private static final Set<String> FIELDS = Set.of(
            "enabled", "weight", "maxConcurrent", "maxCpuMillis", "maxMemoryBytes",
            "maxBacklog", "maxTriggerRatePerSecond", "expectedVersion"
    );
    private static final Set<String> PREVIEW_FIELDS = Set.of(
            "enabled", "weight", "maxConcurrent", "maxCpuMillis", "maxMemoryBytes",
            "maxBacklog", "maxTriggerRatePerSecond", "expectedVersion", "window", "headroomPercent"
    );
    private static final Duration CAPACITY_BUCKET_WIDTH = Duration.ofMinutes(5);

    private final FireflyPluginContext context;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;

    AdminTenantController(FireflyPluginContext context, AdminRequestReader requests,
                          AdminHttpResponder responses) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
    }

    void tenants(HttpExchange exchange) throws IOException {
        TenantQuotaStore store = context.tenantQuotaStore().orElseThrow(() ->
                new AdminHttpException(503, "tenant_governance_unavailable",
                        "tenant governance is not configured"));
        String previewTenant = previewTenantId(exchange);
        if (!previewTenant.isEmpty()) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                responses.respond(exchange, 405, AdminHttpResponder.JSON, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            preview(exchange, store, previewTenant);
            return;
        }
        String tenantId = tenantId(exchange);
        switch (exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT)) {
            case "GET" -> get(exchange, store, tenantId);
            case "PUT" -> put(exchange, store, tenantId);
            default -> responses.respond(exchange, 405, AdminHttpResponder.JSON,
                    "{\"error\":\"method_not_allowed\"}");
        }
    }

    private void preview(HttpExchange exchange, TenantQuotaStore quotaStore, String tenantId) throws IOException {
        exchange.setAttribute("firefly.audit.skip", true);
        TenantCapacityStore capacityStore = context.tenantCapacityStore().orElseThrow(() ->
                new AdminHttpException(503, "tenant_capacity_unavailable",
                        "tenant capacity planning is not configured"));
        TenantDefinition policy = quotaStore.find(tenantId).orElseThrow(() ->
                new AdminHttpException(404, "tenant_not_found", "tenant not found: " + tenantId));
        Map<String, String> request = requests.object(exchange);
        request.keySet().stream().filter(key -> !PREVIEW_FIELDS.contains(key)).findFirst().ifPresent(key -> {
            throw new IllegalArgumentException("unknown tenant preview field: " + key);
        });
        Duration window = parseWindow(request.getOrDefault("window", "P7D"));
        int headroom = integer(request, "headroomPercent", TenantQuotaProposal.DEFAULT_HEADROOM_PERCENT);
        TenantQuotaProposal proposal;
        try {
            proposal = new TenantQuotaProposal(requiredLong(request, "expectedVersion"),
                    requests.booleanValue(request, "enabled", policy.enabled()),
                    integer(request, "weight", policy.weight()),
                    integer(request, "maxConcurrent", policy.maxConcurrent()),
                    number(request, "maxCpuMillis", policy.maxCpuMillis()),
                    number(request, "maxMemoryBytes", policy.maxMemoryBytes()),
                    number(request, "maxBacklog", policy.maxBacklog()),
                    number(request, "maxTriggerRatePerSecond", policy.maxTriggerRatePerSecond()),
                    window, headroom);
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith("headroomPercent")) {
                throw new AdminHttpException(400, "invalid_headroom_percent", invalid.getMessage());
            }
            throw invalid;
        }
        Instant now = context.clock().instant();
        TenantUsage usage = quotaStore.usage(tenantId, now);
        TenantCapacityBaseline baseline = capacityStore.baseline(tenantId, now.minus(window), now,
                CAPACITY_BUCKET_WIDTH);
        TenantQuotaPreview result;
        try {
            result = new TenantQuotaPlanner().preview(policy, usage, baseline, proposal, now);
        } catch (IllegalStateException conflict) {
            throw new AdminHttpException(409, "tenant_version_conflict", conflict.getMessage());
        }
        context.schedulerMetrics().ifPresent(metrics -> metrics.recordTenantQuotaPreview(result.overallRisk().name()));
        responses.ok(exchange, AdminHttpResponder.JSON, previewJson(result));
    }

    private void get(HttpExchange exchange, TenantQuotaStore store, String tenantId) throws IOException {
        Instant now = context.clock().instant();
        if (!tenantId.isEmpty()) {
            TenantDefinition definition = store.find(tenantId).orElseThrow(() ->
                    new AdminHttpException(404, "tenant_not_found", "tenant not found: " + tenantId));
            responses.ok(exchange, AdminHttpResponder.JSON, json(definition, store.usage(tenantId, now)));
            return;
        }
        String body = store.list().stream()
                .map(definition -> json(definition, store.usage(definition.tenantId(), now)))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        responses.ok(exchange, AdminHttpResponder.JSON, body);
    }

    private void put(HttpExchange exchange, TenantQuotaStore store, String tenantId) throws IOException {
        if (tenantId.isEmpty()) {
            throw new AdminHttpException(400, "tenant_id_required", "PUT requires /api/tenants/{tenantId}");
        }
        Map<String, String> request = requests.object(exchange);
        request.keySet().stream().filter(key -> !FIELDS.contains(key)).findFirst().ifPresent(key -> {
            throw new IllegalArgumentException("unknown tenant field: " + key);
        });
        long expectedVersion = requiredLong(request, "expectedVersion");
        TenantDefinition before = store.find(tenantId).orElse(null);
        if (before == null && expectedVersion != 0) {
            throw new AdminHttpException(409, "tenant_version_conflict",
                    "new tenants require expectedVersion=0");
        }
        Instant now = context.clock().instant();
        TenantDefinition base = before == null
                ? new TenantDefinition(tenantId, true, 1, 0, 0, 0, 0, 0, 0, now)
                : before;
        TenantDefinition requested = new TenantDefinition(
                tenantId,
                requests.booleanValue(request, "enabled", base.enabled()),
                integer(request, "weight", base.weight()),
                integer(request, "maxConcurrent", base.maxConcurrent()),
                number(request, "maxCpuMillis", base.maxCpuMillis()),
                number(request, "maxMemoryBytes", base.maxMemoryBytes()),
                number(request, "maxBacklog", base.maxBacklog()),
                number(request, "maxTriggerRatePerSecond", base.maxTriggerRatePerSecond()),
                base.version(), now
        );
        TenantDefinition saved;
        try {
            saved = store.save(requested, expectedVersion, now);
        } catch (IllegalStateException conflict) {
            throw new AdminHttpException(409, "tenant_version_conflict", conflict.getMessage());
        }
        if (before != null) exchange.setAttribute("firefly.audit.before", json(before, store.usage(tenantId, now)));
        String after = json(saved, store.usage(tenantId, now));
        exchange.setAttribute("firefly.audit.after", after);
        responses.respond(exchange, before == null ? 201 : 200, AdminHttpResponder.JSON, after);
    }

    private String json(TenantDefinition definition, TenantUsage usage) {
        return "{\"tenantId\":\"" + responses.escape(definition.tenantId())
                + "\",\"enabled\":" + definition.enabled()
                + ",\"weight\":" + definition.weight()
                + ",\"maxConcurrent\":" + definition.maxConcurrent()
                + ",\"maxCpuMillis\":" + definition.maxCpuMillis()
                + ",\"maxMemoryBytes\":" + definition.maxMemoryBytes()
                + ",\"maxBacklog\":" + definition.maxBacklog()
                + ",\"maxTriggerRatePerSecond\":" + definition.maxTriggerRatePerSecond()
                + ",\"version\":" + definition.version()
                + ",\"updatedAt\":\"" + definition.updatedAt() + "\""
                + ",\"usage\":{\"activeExecutions\":" + usage.activeExecutions()
                + ",\"reservedCpuMillis\":" + usage.reservedCpuMillis()
                + ",\"reservedMemoryBytes\":" + usage.reservedMemoryBytes()
                + ",\"backlog\":" + usage.backlog()
                + ",\"triggersInWindow\":" + usage.triggersInWindow()
                + ",\"triggerWindowStartedAt\":\"" + usage.triggerWindowStartedAt() + "\"}}";
    }

    static String previewJson(TenantQuotaPreview preview) {
        String changes = preview.changes().stream().map(change -> "{\"field\":\""
                + escape(change.field()) + "\",\"before\":\"" + escape(change.before())
                + "\",\"after\":\"" + escape(change.after()) + "\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String resources = preview.resources().stream().map(resource -> {
            String reasons = resource.reasons().stream().map(reason -> "\"" + escape(reason) + "\"")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            return "{\"resource\":\"" + resource.resource().jsonName() + "\",\"current\":"
                    + resource.current() + ",\"average\":" + resource.average() + ",\"peak\":"
                    + resource.peak() + ",\"proposedLimit\":" + resource.proposedLimit()
                    + ",\"recommendedLimit\":" + (resource.recommendedLimit() == null
                    ? "null" : resource.recommendedLimit()) + ",\"saturationRatio\":"
                    + resource.saturationRatio() + ",\"rejections\":" + resource.rejections()
                    + ",\"quality\":\"" + resource.quality() + "\",\"risk\":\"" + resource.risk()
                    + "\",\"reasons\":" + reasons + "}";
        }).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String warnings = preview.warnings().stream().map(value -> "\"" + escape(value) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "{\"tenantId\":\"" + escape(preview.tenantId()) + "\",\"evaluatedAt\":\""
                + preview.evaluatedAt() + "\",\"baseVersion\":" + preview.baseVersion()
                + ",\"window\":{\"from\":\"" + preview.windowFrom() + "\",\"to\":\""
                + preview.windowTo() + "\",\"coverageRatio\":" + preview.coverageRatio()
                + "},\"overallRisk\":\"" + preview.overallRisk() + "\",\"changes\":" + changes
                + ",\"resources\":" + resources + ",\"warnings\":" + warnings + "}";
    }

    private static String previewTenantId(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String suffix = "/quota-preview";
        if (!path.startsWith(BASE + "/") || !path.endsWith(suffix)) return "";
        String encoded = path.substring((BASE + "/").length(), path.length() - suffix.length());
        String value = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        if (value.isBlank() || value.contains("/")) {
            throw new AdminHttpException(400, "invalid_tenant_id", "tenantId must be one path segment");
        }
        return value;
    }

    private static Duration parseWindow(String value) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.compareTo(Duration.ofHours(24)) < 0 || duration.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException("capacity window must be between P1D and P30D");
            }
            return duration;
        } catch (RuntimeException invalid) {
            throw new AdminHttpException(400, "invalid_capacity_window", "invalid capacity window: " + value);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String tenantId(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(BASE) || path.equals(BASE + "/")) return "";
        if (!path.startsWith(BASE + "/")) return "";
        String value = URLDecoder.decode(path.substring((BASE + "/").length()), StandardCharsets.UTF_8);
        if (value.isBlank() || value.contains("/")) {
            throw new AdminHttpException(400, "invalid_tenant_id", "tenantId must be one path segment");
        }
        return value;
    }

    private static long requiredLong(Map<String, String> request, String field) {
        if (!request.containsKey(field)) throw new IllegalArgumentException("missing required field: " + field);
        return number(request, field, 0);
    }

    private static int integer(Map<String, String> request, String field, int defaultValue) {
        long value = number(request, field, defaultValue);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(field + " exceeds integer range");
        return (int) value;
    }

    private static long number(Map<String, String> request, String field, long defaultValue) {
        String value = request.get(field);
        if (value == null) return defaultValue;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new IllegalArgumentException(field + " must not be negative");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }
}
