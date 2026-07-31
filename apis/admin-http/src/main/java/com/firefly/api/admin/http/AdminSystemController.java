package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;

/** Operational endpoints that describe the Admin plugin and server runtime. */
final class AdminSystemController {
    private final FireflyPluginContext context;
    private final AdminHttpResponder responses;

    AdminSystemController(FireflyPluginContext context, AdminHttpResponder responses) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
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
        var records = context.auditRepository().map(repository -> repository.listRecent(200)).orElse(List.of());
        responses.ok(exchange, AdminHttpResponder.JSON, AdminHttpJson.audit(records));
    }

    private void methodNotAllowed(HttpExchange exchange) throws IOException {
        responses.respond(exchange, 405, AdminHttpResponder.JSON, "{\"error\":\"method_not_allowed\"}");
    }
}
