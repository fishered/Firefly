package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;

/** Operational endpoints that describe the Admin plugin and server runtime. */
final class AdminSystemController {
    private final FireflyPluginContext context;
    private final AdminHttpResponder responses;
    private final AdminRequestReader requests;

    AdminSystemController(FireflyPluginContext context, AdminHttpResponder responses,
                          AdminRequestReader requests) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
    }

    void index(HttpExchange exchange) throws IOException {
        responses.respond(exchange, 404, AdminHttpResponder.JSON, "{\"error\":\"admin_ui_is_external\"}");
    }

    void health(HttpExchange exchange) throws IOException {
        responses.ok(exchange, AdminHttpResponder.JSON, "{\"status\":\"UP\",\"plugin\":\"admin-http\"}");
    }

    void plugins(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        var plugins = context.pluginStatusProvider()
                .map(com.firefly.plugin.PluginStatusProvider::plugins).orElse(List.of());
        responses.ok(exchange, AdminHttpResponder.JSON, AdminHttpJson.plugins(plugins));
    }

    void audit(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange);
            return;
        }
        var query = new com.firefly.audit.AuditQuery(
                AdminPageSupport.text(requests, exchange, "actor"),
                AdminPageSupport.text(requests, exchange, "action"),
                AdminPageSupport.text(requests, exchange, "resourceType"),
                AdminPageSupport.text(requests, exchange, "outcome"),
                AdminPageSupport.instant(requests, exchange, "from"),
                AdminPageSupport.instant(requests, exchange, "to"),
                AdminPageSupport.cursor(requests, exchange),
                AdminPageSupport.limit(requests, exchange, 100)
        );
        var page = context.auditRepository().map(repository -> repository.query(query))
                .orElse(new com.firefly.query.CursorPage<com.firefly.audit.AuditRecord>(List.of(), null));
        responses.ok(exchange, AdminHttpResponder.JSON,
                AdminPageSupport.json(AdminHttpJson.audit(page.items()), page));
    }

    private void methodNotAllowed(HttpExchange exchange) throws IOException {
        responses.respond(exchange, 405, AdminHttpResponder.JSON, "{\"error\":\"method_not_allowed\"}");
    }
}
