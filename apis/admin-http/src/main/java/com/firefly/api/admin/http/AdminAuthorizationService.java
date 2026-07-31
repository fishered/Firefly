package com.firefly.api.admin.http;

import com.firefly.api.admin.http.routing.AdminRequestTarget;
import com.firefly.api.admin.http.routing.AdminRoutePolicy;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.security.IntegrationKeyService;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;

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

    Authorization authorize(HttpExchange exchange, AdminRoutePolicy policy, AdminRequestTarget target) {
        if (policy.allowsAnonymous(target)) {
            return Authorization.ALLOWED;
        }
        String integrationKey = exchange.getRequestHeaders().getFirst("X-Firefly-Integration-Key");
        if (integrationKey != null && !integrationKey.isBlank()) {
            boolean valid = integrationKeys != null && integrationKeys.verify(integrationKey);
            exchange.setAttribute("firefly.admin.role", valid ? "INTEGRATION" : "UNKNOWN");
            exchange.setAttribute("firefly.admin.actor", "integration-key");
            return new Authorization(valid, valid && policy.allowsIntegrationKey(target));
        }
        String token = exchange.getRequestHeaders().getFirst("X-Firefly-Token");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if ((token == null || token.isBlank()) && authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring("Bearer ".length());
        }
        if (options.jwtService() != null && token != null && !token.isBlank()) {
            return authorizeJwt(exchange, token, policy, target);
        }
        if (options.tokenRoles().isEmpty() && options.jwtService() == null) {
            exchange.setAttribute("firefly.admin.role", "UNRESTRICTED");
            return Authorization.ALLOWED;
        }
        AdminRole role = roleForToken(token);
        exchange.setAttribute("firefly.admin.role", role == null ? "UNKNOWN" : role.name());
        if (role == null) return Authorization.DENIED;
        return new Authorization(true, role.allows(policy.requiredRole(target)));
    }

    private Authorization authorizeJwt(
            HttpExchange exchange,
            String token,
            AdminRoutePolicy policy,
            AdminRequestTarget target
    ) {
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
            if (policy.allowsPasswordChange(target)) {
                return Authorization.ALLOWED;
            }
            if (user.passwordChangeRequired()) {
                exchange.setAttribute("firefly.authorization.error", "password_change_required");
                return new Authorization(true, false);
            }
            return new Authorization(true, principal.allows(requiredJwtRole(policy.requiredRole(target))));
        } catch (IllegalArgumentException invalidJwt) {
            return Authorization.DENIED;
        }
    }

    private com.firefly.security.FireflyRole requiredJwtRole(AdminRole requiredRole) {
        return switch (requiredRole) {
            case READER -> com.firefly.security.FireflyRole.READER;
            case OPERATOR -> com.firefly.security.FireflyRole.OPERATOR;
            case ADMIN -> com.firefly.security.FireflyRole.ADMIN;
        };
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

    record Authorization(boolean authenticated, boolean allowed) {
        private static final Authorization ALLOWED = new Authorization(true, true);
        private static final Authorization DENIED = new Authorization(false, false);
    }
}
