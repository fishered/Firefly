package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import com.firefly.security.IntegrationKeyService;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

final class AdminAuthorizationService {
    private final AdminHttpOptions options;
    private final FireflyPluginContext context;
    private final IntegrationKeyService integrationKeys;

    AdminAuthorizationService(
            AdminHttpOptions options,
            FireflyPluginContext context,
            IntegrationKeyService integrationKeys
    ) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.integrationKeys = integrationKeys;
    }

    Authorization authorize(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if ("/api/health".equals(path) || "/api/auth/config".equals(path) || "/api/auth/login".equals(path)) {
            return Authorization.ALLOWED;
        }
        String integrationKey = exchange.getRequestHeaders().getFirst("X-Firefly-Integration-Key");
        if (integrationKey != null && !integrationKey.isBlank()) {
            boolean valid = integrationKeys != null && integrationKeys.verify(integrationKey);
            exchange.setAttribute("firefly.admin.role", valid ? "INTEGRATION" : "UNKNOWN");
            exchange.setAttribute("firefly.admin.actor", "integration-key");
            return new Authorization(valid, valid && integrationJobSyncAllowed(exchange));
        }
        String token = exchange.getRequestHeaders().getFirst("X-Firefly-Token");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if ((token == null || token.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring("Bearer ".length());
        }
        if (options.jwtService() != null && token != null && !token.isBlank()) {
            return authorizeJwt(exchange, token, path);
        }
        if (options.tokenRoles().isEmpty() && options.jwtService() == null) {
            exchange.setAttribute("firefly.admin.role", "UNRESTRICTED");
            return Authorization.ALLOWED;
        }
        AdminRole role = roleForToken(token);
        exchange.setAttribute("firefly.admin.role", role == null ? "UNKNOWN" : role.name());
        if (role == null) return Authorization.DENIED;
        return new Authorization(true, role.allows(requiredRole(exchange)));
    }

    private Authorization authorizeJwt(HttpExchange exchange, String token, String path) {
        try {
            com.firefly.security.FireflyPrincipal principal = options.jwtService().verify(token);
            com.firefly.security.AdminUser user = context.adminUserRepository()
                    .flatMap(repository -> repository.find(principal.subject())).orElse(null);
            if (user == null || !user.enabled() || user.version() != principal.identityVersion()
                    || !user.roles().equals(principal.roles())) {
                return Authorization.DENIED;
            }
            exchange.setAttribute("firefly.principal", principal);
            exchange.setAttribute("firefly.admin.role", principal.roles().toString());
            exchange.setAttribute("firefly.admin.actor", principal.subject());
            if ("/api/auth/password".equals(path)) {
                return new Authorization(true, "POST".equalsIgnoreCase(exchange.getRequestMethod()));
            }
            if (user.passwordChangeRequired()) {
                exchange.setAttribute("firefly.authorization.error", "password_change_required");
                return new Authorization(true, false);
            }
            return new Authorization(true, principal.allows(requiredJwtRole(exchange)));
        } catch (IllegalArgumentException invalidJwt) {
            return Authorization.DENIED;
        }
    }

    private com.firefly.security.FireflyRole requiredJwtRole(HttpExchange exchange) {
        return switch (requiredRole(exchange)) {
            case READER -> com.firefly.security.FireflyRole.READER;
            case OPERATOR -> com.firefly.security.FireflyRole.OPERATOR;
            case ADMIN -> com.firefly.security.FireflyRole.ADMIN;
        };
    }

    private boolean integrationJobSyncAllowed(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        return ((Set.of("GET", "HEAD").contains(method) && path.startsWith("/api/jobs/"))
                || ("POST".equals(method) && "/api/jobs".equals(path))
                || ("PUT".equals(method) && path.startsWith("/api/jobs/")
                && !path.substring("/api/jobs/".length()).contains("/")));
    }

    private AdminRole roleForToken(String provided) {
        if (provided == null) return null;
        byte[] candidate = provided.getBytes(StandardCharsets.UTF_8);
        return options.tokenRoles().entrySet().stream()
                .filter(entry -> java.security.MessageDigest.isEqual(
                        entry.getKey().getBytes(StandardCharsets.UTF_8), candidate
                ))
                .map(Map.Entry::getValue)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(null);
    }

    private AdminRole requiredRole(HttpExchange exchange) {
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/users") || path.startsWith("/api/integration-key")) return AdminRole.ADMIN;
        if ("GET".equals(method) || "HEAD".equals(method)) return AdminRole.READER;
        if ("PATCH".equals(method) || "PUT".equals(method)
                || ("POST".equals(method) && path.endsWith("/trigger"))
                || ("POST".equals(method) && path.startsWith("/api/executions/") && path.endsWith("/cancel"))
                || ("POST".equals(method) && path.equals("/api/executions/batch-cancel"))
                || ("POST".equals(method) && path.equals("/api/outbox/batch-requeue"))
                || ("POST".equals(method) && path.startsWith("/api/outbox/") && path.endsWith("/requeue"))) {
            return AdminRole.OPERATOR;
        }
        return AdminRole.ADMIN;
    }

    record Authorization(boolean authenticated, boolean allowed) {
        private static final Authorization ALLOWED = new Authorization(true, true);
        private static final Authorization DENIED = new Authorization(false, false);
    }
}
