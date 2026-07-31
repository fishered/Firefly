package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.ScheduledJobRecord;
import com.sun.net.httpserver.HttpExchange;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

final class AdminAuditService {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(
            "com.firefly.audit.admin"
    );

    private final FireflyPluginContext context;

    AdminAuditService(FireflyPluginContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
    }

    void auditMutation(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(method) || "HEAD".equals(method)) return;
        Object status = exchange.getAttribute("firefly.response.status");
        Object role = exchange.getAttribute("firefly.admin.role");
        LOG.info(() -> "admin_mutation method=" + method
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
                        resourceType(exchange), resourceId(exchange),
                        responseStatus >= 200 && responseStatus < 300 ? "SUCCESS" : "FAILURE",
                        attribute(exchange, "firefly.audit.before"),
                        attribute(exchange, "firefly.audit.after"),
                        "status=" + responseStatus + ", remote=" + exchange.getRemoteAddress()
                ));
            } catch (RuntimeException failure) {
                LOG.log(java.util.logging.Level.SEVERE, "failed to persist admin audit record", failure);
            }
        });
    }

    void recordJobHistory(
            HttpExchange exchange,
            String jobId,
            String action,
            ScheduledJobRecord before,
            ScheduledJobRecord after
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

    String actor(HttpExchange exchange) {
        Object authenticatedActor = exchange.getAttribute("firefly.admin.actor");
        if (authenticatedActor != null) return authenticatedActor.toString();
        String actor = exchange.getRequestHeaders().getFirst("X-Firefly-Actor");
        return actor == null || actor.isBlank() ? exchange.getRemoteAddress().toString() : actor;
    }

    static String attribute(HttpExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value == null ? "" : value.toString();
    }

    private String resourceType(HttpExchange exchange) {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        return parts.length > 2 ? parts[2] : "admin";
    }

    private String resourceId(HttpExchange exchange) {
        String[] parts = exchange.getRequestURI().getPath().split("/");
        return parts.length > 3 ? URLDecoder.decode(parts[3], StandardCharsets.UTF_8) : "";
    }
}
