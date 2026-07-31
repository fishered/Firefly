package com.firefly.api.admin.http;

import com.firefly.api.admin.http.routing.AdminRequestTarget;
import com.firefly.api.admin.http.routing.AdminRoutePolicy;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

final class AdminHttpDispatcher {
    private final AdminRequestReader requestReader;
    private final AdminAuthorizationService authorization;
    private final AdminAuditService audit;
    private final AdminHttpResponder responder;

    AdminHttpDispatcher(
            AdminRequestReader requestReader,
            AdminAuthorizationService authorization,
            AdminAuditService audit,
            AdminHttpResponder responder
    ) {
        this.requestReader = java.util.Objects.requireNonNull(requestReader, "requestReader");
        this.authorization = java.util.Objects.requireNonNull(authorization, "authorization");
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
        this.responder = java.util.Objects.requireNonNull(responder, "responder");
    }

    void dispatch(
            HttpExchange exchange,
            String routePath,
            AdminRoutePolicy policy,
            AdminExchangeHandler handler
    ) throws IOException {
        try {
            requestReader.validate(exchange);
            AdminRequestTarget target = new AdminRequestTarget(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), routePath
            );
            AdminAuthorizationService.Authorization result = authorization.authorize(exchange, policy, target);
            if (!result.authenticated()) {
                responder.respond(exchange, 401, AdminHttpResponder.JSON, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!result.allowed()) {
                String error = AdminAuditService.attribute(exchange, "firefly.authorization.error");
                responder.respond(exchange, 403, AdminHttpResponder.JSON,
                        "{\"error\":\"" + responder.escape(error.isBlank() ? "forbidden" : error) + "\"}");
                return;
            }
            handler.handle(exchange);
        } catch (AdminHttpException failure) {
            responder.respond(exchange, failure.status(), AdminHttpResponder.JSON,
                    "{\"error\":\"" + responder.escape(failure.error()) + "\",\"message\":\""
                            + responder.escape(failure.getMessage()) + "\"}");
        } catch (IllegalArgumentException failure) {
            responder.respond(exchange, 400, AdminHttpResponder.JSON,
                    "{\"error\":\"bad_request\",\"message\":\""
                            + responder.escape(failure.getMessage()) + "\"}");
        } catch (SecurityException failure) {
            responder.respond(exchange, 403, AdminHttpResponder.JSON,
                    "{\"error\":\"forbidden\",\"message\":\""
                            + responder.escape(failure.getMessage()) + "\"}");
        } catch (Exception failure) {
            responder.respond(exchange, 500, AdminHttpResponder.JSON,
                    "{\"error\":\"internal_error\",\"message\":\""
                            + responder.escape(failure.getMessage()) + "\"}");
        } finally {
            audit.auditMutation(exchange);
        }
    }
}
