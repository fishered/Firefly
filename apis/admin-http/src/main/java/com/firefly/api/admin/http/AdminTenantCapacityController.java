package com.firefly.api.admin.http;

import com.firefly.governance.TenantCapacityBaseline;
import com.firefly.governance.TenantCapacityRisk;
import com.firefly.governance.TenantCapacityStore;
import com.firefly.governance.TenantDefinition;
import com.firefly.governance.TenantQuotaPlanner;
import com.firefly.governance.TenantQuotaPreview;
import com.firefly.governance.TenantQuotaProposal;
import com.firefly.governance.TenantQuotaStore;
import com.firefly.plugin.FireflyPluginContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only all-tenant capacity report and bounded export. */
final class AdminTenantCapacityController {
    private static final String BASE = "/api/tenant-capacity";
    private static final Duration BUCKET_WIDTH = Duration.ofMinutes(5);
    private static final int MAX_LIMIT = 500;
    private static final int MAX_EXPORT_TENANTS = 1_000;
    private static final int MAX_EXPORT_BYTES = 2 * 1024 * 1024;

    private final FireflyPluginContext context;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;

    AdminTenantCapacityController(
            FireflyPluginContext context, AdminRequestReader requests, AdminHttpResponder responses
    ) {
        this.context = Objects.requireNonNull(context, "context");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.responses = Objects.requireNonNull(responses, "responses");
    }

    void capacity(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            responses.respond(exchange, 405, AdminHttpResponder.JSON, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        TenantQuotaStore quotas = context.tenantQuotaStore().orElseThrow(() ->
                new AdminHttpException(503, "tenant_governance_unavailable", "tenant governance is not configured"));
        TenantCapacityStore capacity = context.tenantCapacityStore().orElseThrow(() ->
                new AdminHttpException(503, "tenant_capacity_unavailable", "tenant capacity is not configured"));
        Duration window = window(requests.queryParameter(exchange, "window"));
        Instant now = context.clock().instant();
        String relative = relative(exchange);
        if ("/export".equals(relative)) {
            export(exchange, quotas, capacity, window, now);
        } else if (!relative.isEmpty()) {
            detail(exchange, quotas, capacity, decodeSegment(relative.substring(1)), window, now);
        } else {
            list(exchange, quotas, capacity, window, now);
        }
    }

    private void list(
            HttpExchange exchange, TenantQuotaStore quotas, TenantCapacityStore capacity,
            Duration window, Instant now
    ) throws IOException {
        int limit = integer(requests.queryParameter(exchange, "limit"), 100);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new AdminHttpException(400, "invalid_capacity_limit", "limit must be between 1 and 500");
        }
        TenantCapacityRisk risk = risk(requests.queryParameter(exchange, "risk"));
        List<TenantQuotaPreview> values = previews(quotas, capacity, window, now).stream()
                .filter(value -> risk == null || value.overallRisk() == risk)
                .sorted(reportOrder()).toList();
        String cursor = requests.queryParameter(exchange, "cursor");
        if (!cursor.isBlank()) values = after(values, cursor);
        List<TenantQuotaPreview> page = values.stream().limit(limit).toList();
        String next = values.size() > limit ? encodeCursor(page.getLast()) : "";
        String items = page.stream().map(AdminTenantController::previewJson)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        responses.ok(exchange, AdminHttpResponder.JSON, "{\"items\":" + items + ",\"nextCursor\":\""
                + escape(next) + "\"}");
    }

    private void detail(
            HttpExchange exchange, TenantQuotaStore quotas, TenantCapacityStore capacity,
            String tenantId, Duration window, Instant now
    ) throws IOException {
        TenantDefinition policy = quotas.find(tenantId).orElseThrow(() ->
                new AdminHttpException(404, "tenant_not_found", "tenant not found: " + tenantId));
        responses.ok(exchange, AdminHttpResponder.JSON,
                AdminTenantController.previewJson(currentPreview(policy, quotas, capacity, window, now)));
    }

    private void export(
            HttpExchange exchange, TenantQuotaStore quotas, TenantCapacityStore capacity,
            Duration window, Instant now
    ) throws IOException {
        List<TenantQuotaPreview> values = previews(quotas, capacity, window, now).stream()
                .sorted(reportOrder()).toList();
        if (values.size() > MAX_EXPORT_TENANTS) {
            throw new AdminHttpException(413, "capacity_export_limit_exceeded",
                    "capacity export exceeds 1000 tenants");
        }
        String format = requests.queryParameter(exchange, "format");
        String body;
        String contentType;
        if (format.isBlank() || "json".equalsIgnoreCase(format)) {
            body = values.stream().map(AdminTenantController::previewJson)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            contentType = AdminHttpResponder.JSON;
        } else if ("csv".equalsIgnoreCase(format)) {
            StringBuilder csv = new StringBuilder("tenantId,overallRisk,coverageRatio,resource,current,average,peak,proposedLimit,recommendedLimit,quality,risk,rejections\n");
            for (TenantQuotaPreview preview : values) {
                for (TenantQuotaPreview.ResourcePreview resource : preview.resources()) {
                    csv.append(csv(preview.tenantId())).append(',').append(preview.overallRisk()).append(',')
                            .append(preview.coverageRatio()).append(',').append(resource.resource().jsonName()).append(',')
                            .append(resource.current()).append(',').append(resource.average()).append(',')
                            .append(resource.peak()).append(',').append(resource.proposedLimit()).append(',')
                            .append(resource.recommendedLimit() == null ? "" : resource.recommendedLimit()).append(',')
                            .append(resource.quality()).append(',').append(resource.risk()).append(',')
                            .append(resource.rejections()).append('\n');
                }
            }
            body = csv.toString();
            contentType = "text/csv; charset=utf-8";
        } else {
            throw new AdminHttpException(400, "invalid_capacity_export_format", "format must be json or csv");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_EXPORT_BYTES) {
            throw new AdminHttpException(413, "capacity_export_limit_exceeded",
                    "capacity export exceeds 2 MiB");
        }
        responses.ok(exchange, contentType, body);
    }

    private List<TenantQuotaPreview> previews(
            TenantQuotaStore quotas, TenantCapacityStore capacity, Duration window, Instant now
    ) {
        List<TenantDefinition> policies = quotas.list();
        List<TenantCapacityBaseline> baselines = capacity.baselines(
                policies.stream().map(TenantDefinition::tenantId).toList(), now.minus(window), now, BUCKET_WIDTH);
        List<TenantQuotaPreview> result = new ArrayList<>(policies.size());
        for (int index = 0; index < policies.size(); index++) {
            TenantDefinition policy = policies.get(index);
            result.add(currentPreview(policy, quotas, baselines.get(index), window, now));
        }
        return List.copyOf(result);
    }

    private TenantQuotaPreview currentPreview(
            TenantDefinition policy, TenantQuotaStore quotas, TenantCapacityStore capacity,
            Duration window, Instant now
    ) {
        TenantCapacityBaseline baseline = capacity.baseline(policy.tenantId(), now.minus(window), now, BUCKET_WIDTH);
        return currentPreview(policy, quotas, baseline, window, now);
    }

    private TenantQuotaPreview currentPreview(
            TenantDefinition policy, TenantQuotaStore quotas, TenantCapacityBaseline baseline,
            Duration window, Instant now
    ) {
        TenantQuotaProposal proposal = new TenantQuotaProposal(policy.version(), policy.enabled(), policy.weight(),
                policy.maxConcurrent(), policy.maxCpuMillis(), policy.maxMemoryBytes(), policy.maxBacklog(),
                policy.maxTriggerRatePerSecond(), window, TenantQuotaProposal.DEFAULT_HEADROOM_PERCENT);
        return new TenantQuotaPlanner().preview(policy, quotas.usage(policy.tenantId(), now), baseline, proposal, now);
    }

    private static Comparator<TenantQuotaPreview> reportOrder() {
        return Comparator.comparingInt((TenantQuotaPreview value) -> value.overallRisk().severity()).reversed()
                .thenComparing(TenantQuotaPreview::tenantId);
    }

    private static List<TenantQuotaPreview> after(List<TenantQuotaPreview> values, String encoded) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            TenantCapacityRisk risk = TenantCapacityRisk.valueOf(decoded.substring(0, separator));
            String tenant = decoded.substring(separator + 1);
            TenantQuotaPreview marker = values.stream()
                    .filter(value -> value.overallRisk() == risk && value.tenantId().equals(tenant))
                    .findFirst().orElseThrow();
            return values.stream().dropWhile(value -> reportOrder().compare(value, marker) <= 0).toList();
        } catch (RuntimeException invalid) {
            throw new AdminHttpException(400, "invalid_capacity_cursor", "invalid capacity cursor");
        }
    }

    private static String encodeCursor(TenantQuotaPreview value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value.overallRisk() + "|" + value.tenantId()).getBytes(StandardCharsets.UTF_8));
    }

    private static Duration window(String value) {
        String normalized = value == null || value.isBlank() ? "P7D" : value;
        try {
            Duration result = Duration.parse(normalized);
            if (result.compareTo(Duration.ofDays(1)) < 0 || result.compareTo(Duration.ofDays(30)) > 0) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException invalid) {
            throw new AdminHttpException(400, "invalid_capacity_window", "window must be P1D..P30D");
        }
    }

    private static TenantCapacityRisk risk(String value) {
        if (value == null || value.isBlank()) return null;
        try { return TenantCapacityRisk.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) {
            throw new AdminHttpException(400, "invalid_capacity_risk", "invalid risk filter");
        }
    }

    private static int integer(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) {
            throw new AdminHttpException(400, "invalid_capacity_limit", "limit must be an integer");
        }
    }

    private static String relative(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        return path.length() <= BASE.length() ? "" : path.substring(BASE.length());
    }

    private static String decodeSegment(String value) {
        String decoded = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        if (decoded.isBlank() || decoded.contains("/")) {
            throw new AdminHttpException(400, "invalid_tenant_id", "tenantId must be one path segment");
        }
        return decoded;
    }

    private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
