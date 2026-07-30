package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import com.firefly.security.IntegrationKeyService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

final class AdminAuthController {
    private final AdminHttpOptions options;
    private final FireflyPluginContext context;
    private final IntegrationKeyService integrationKeys;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;
    private final AdminAuditService audit;
    private final com.firefly.security.Pbkdf2PasswordHasher passwordHasher =
            new com.firefly.security.Pbkdf2PasswordHasher();

    AdminAuthController(
            AdminHttpOptions options,
            FireflyPluginContext context,
            IntegrationKeyService integrationKeys,
            AdminRequestReader requests,
            AdminHttpResponder responses,
            AdminAuditService audit
    ) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.integrationKeys = integrationKeys;
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
        this.audit = java.util.Objects.requireNonNull(audit, "audit");
    }

    void config(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        respond(exchange, 200, "{\"enabled\":" + (options.jwtService() != null) + "}");
    }

    void integrationKey(HttpExchange exchange) throws IOException {
        com.firefly.security.IntegrationKeyRepository repository = context.integrationKeyRepository()
                .orElseThrow(() -> new IllegalStateException("integrationKeyRepository is required"));
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            com.firefly.security.IntegrationKeyRecord record = repository.find().orElse(null);
            if (record == null) {
                respond(exchange, 200, "{\"configured\":false}");
                return;
            }
            respond(exchange, 200, "{\"configured\":true,\"version\":" + record.version()
                    + ",\"updatedAt\":\"" + record.updatedAt() + "\"}");
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (integrationKeys == null) {
                throw new IllegalStateException("integrationKeyService is required");
            }
            IntegrationKeyService.RotatedIntegrationKey rotated = integrationKeys.rotate();
            exchange.setAttribute("firefly.audit.after",
                    "{\"configured\":true,\"version\":" + rotated.version() + "}");
            respond(exchange, 200, "{\"integrationKey\":\"" + responses.escape(rotated.plaintext())
                    + "\",\"version\":" + rotated.version()
                    + ",\"updatedAt\":\"" + rotated.updatedAt() + "\"}");
            return;
        }
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    void login(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (options.jwtService() == null || context.adminUserRepository().isEmpty()) {
            respond(exchange, 404, "{\"error\":\"admin_authentication_disabled\"}");
            return;
        }
        Map<String, String> request = requests.object(exchange);
        String username = required(request, "username").trim();
        char[] password = required(request, "password").toCharArray();
        try {
            com.firefly.security.AdminUser user = context.adminUserRepository().orElseThrow()
                    .find(username).orElse(null);
            if (user == null || !user.enabled() || !passwordHasher.verify(password, user.passwordHash())) {
                respond(exchange, 401, "{\"error\":\"invalid_credentials\"}");
                return;
            }
            String token = options.jwtService().issueUser(user.username(), user.roles(), user.version());
            exchange.setAttribute("firefly.admin.role", "LOGIN");
            exchange.setAttribute("firefly.admin.actor", user.username());
            respond(exchange, 200, "{\"accessToken\":\"" + responses.escape(token)
                    + "\",\"tokenType\":\"Bearer\",\"expiresIn\":"
                    + options.jwtService().expiresInSeconds()
                    + ",\"passwordChangeRequired\":" + user.passwordChangeRequired() + "}");
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    void passwordChange(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        com.firefly.security.AdminUserRepository repository = context.adminUserRepository()
                .orElseThrow(() -> new IllegalStateException("Admin user repository is unavailable"));
        com.firefly.security.FireflyPrincipal principal =
                (com.firefly.security.FireflyPrincipal) exchange.getAttribute("firefly.principal");
        if (principal == null) {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        com.firefly.security.AdminUser current = repository.find(principal.subject()).orElse(null);
        if (current == null || !current.enabled()) {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        Map<String, String> request = requests.object(exchange);
        char[] currentPassword = required(request, "currentPassword").toCharArray();
        char[] newPassword = required(request, "newPassword").toCharArray();
        try {
            if (!passwordHasher.verify(currentPassword, current.passwordHash())) {
                respond(exchange, 401, "{\"error\":\"invalid_credentials\"}");
                return;
            }
            if (newPassword.length < 8 || newPassword.length > 256) {
                throw new IllegalArgumentException("newPassword must contain 8-256 characters");
            }
            if (passwordHasher.verify(newPassword, current.passwordHash())) {
                throw new IllegalArgumentException("newPassword must differ from the current password");
            }
            com.firefly.security.AdminUser updated = new com.firefly.security.AdminUser(
                    current.username(), passwordHasher.hash(newPassword), current.roles(), current.enabled(),
                    false, current.version() + 1, current.createdAt(), context.clock().instant()
            );
            if (!repository.update(updated, current.version())) {
                respond(exchange, 409, "{\"error\":\"user_version_conflict\"}");
                return;
            }
            exchange.setAttribute("firefly.audit.before", AdminHttpJson.user(current));
            exchange.setAttribute("firefly.audit.after", AdminHttpJson.user(updated));
            respond(exchange, 200, "{\"status\":\"password_changed\"}");
        } finally {
            java.util.Arrays.fill(currentPassword, '\0');
            java.util.Arrays.fill(newPassword, '\0');
        }
    }

    void users(HttpExchange exchange) throws IOException {
        com.firefly.security.AdminUserRepository repository = context.adminUserRepository()
                .orElseThrow(() -> new IllegalStateException("Admin user repository is unavailable"));
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
        String prefix = "/api/users/";
        if ("GET".equals(method) && "/api/users".equals(path)) {
            respond(exchange, 200, AdminHttpJson.users(repository.list()));
            return;
        }
        if ("POST".equals(method) && "/api/users".equals(path)) {
            createUser(exchange, repository);
            return;
        }
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            respond(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        String username = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        com.firefly.security.AdminUser current = repository.find(username).orElse(null);
        if (current == null) {
            respond(exchange, 404, "{\"error\":\"user_not_found\"}");
            return;
        }
        if ("PUT".equals(method)) {
            updateUser(exchange, repository, current, username);
            return;
        }
        if ("DELETE".equals(method)) {
            deleteUser(exchange, repository, current, username);
            return;
        }
        respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private void createUser(HttpExchange exchange, com.firefly.security.AdminUserRepository repository)
            throws IOException {
        Map<String, String> request = requests.object(exchange);
        String username = required(request, "username").trim();
        char[] password = required(request, "password").toCharArray();
        try {
            Instant now = context.clock().instant();
            com.firefly.security.AdminUser user = new com.firefly.security.AdminUser(
                    username, passwordHasher.hash(password), adminRoles(required(request, "roles")),
                    Boolean.parseBoolean(request.getOrDefault("enabled", "true")), 0, now, now
            );
            if (!repository.create(user)) {
                respond(exchange, 409, "{\"error\":\"user_already_exists\"}");
                return;
            }
            exchange.setAttribute("firefly.audit.after", AdminHttpJson.user(user));
            respond(exchange, 201, AdminHttpJson.user(user));
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void updateUser(
            HttpExchange exchange,
            com.firefly.security.AdminUserRepository repository,
            com.firefly.security.AdminUser current,
            String username
    ) throws IOException {
        Map<String, String> request = requests.object(exchange);
        long expectedVersion = Long.parseLong(required(request, "version"));
        boolean enabled = Boolean.parseBoolean(request.getOrDefault("enabled", String.valueOf(current.enabled())));
        Set<com.firefly.security.FireflyRole> roles = request.containsKey("roles")
                ? adminRoles(request.get("roles")) : current.roles();
        if (audit.actor(exchange).equals(username) && !enabled) {
            throw new IllegalArgumentException("the current user cannot disable itself");
        }
        ensureAdminRemains(repository, current, roles, enabled);
        String passwordHash = current.passwordHash();
        String rawPassword = request.getOrDefault("password", "");
        if (!rawPassword.isBlank()) {
            char[] password = rawPassword.toCharArray();
            try {
                passwordHash = passwordHasher.hash(password);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        }
        boolean passwordChangeRequired = rawPassword.isBlank() && current.passwordChangeRequired();
        com.firefly.security.AdminUser updated = new com.firefly.security.AdminUser(
                username, passwordHash, roles, enabled, passwordChangeRequired, expectedVersion + 1,
                current.createdAt(), context.clock().instant()
        );
        if (!repository.update(updated, expectedVersion)) {
            respond(exchange, 409, "{\"error\":\"user_version_conflict\"}");
            return;
        }
        exchange.setAttribute("firefly.audit.before", AdminHttpJson.user(current));
        exchange.setAttribute("firefly.audit.after", AdminHttpJson.user(updated));
        respond(exchange, 200, AdminHttpJson.user(updated));
    }

    private void deleteUser(
            HttpExchange exchange,
            com.firefly.security.AdminUserRepository repository,
            com.firefly.security.AdminUser current,
            String username
    ) throws IOException {
        if (audit.actor(exchange).equals(username)) {
            throw new IllegalArgumentException("the current user cannot delete itself");
        }
        long expectedVersion = Long.parseLong(required(requests.object(exchange), "version"));
        ensureAdminRemains(repository, current, Set.of(), false);
        if (!repository.delete(username, expectedVersion)) {
            respond(exchange, 409, "{\"error\":\"user_version_conflict\"}");
            return;
        }
        exchange.setAttribute("firefly.audit.before", AdminHttpJson.user(current));
        respond(exchange, 200, "{\"status\":\"deleted\"}");
    }

    private Set<com.firefly.security.FireflyRole> adminRoles(String value) {
        String normalized = value == null ? "" : value.replace("[", "").replace("]", "");
        Set<com.firefly.security.FireflyRole> roles = java.util.Arrays.stream(normalized.split(","))
                .map(String::trim).filter(role -> !role.isEmpty())
                .map(role -> com.firefly.security.FireflyRole.valueOf(role.toUpperCase(java.util.Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("roles must contain READER, OPERATOR, or ADMIN only");
        }
        return roles;
    }

    private void ensureAdminRemains(
            com.firefly.security.AdminUserRepository repository,
            com.firefly.security.AdminUser current,
            Set<com.firefly.security.FireflyRole> replacementRoles,
            boolean replacementEnabled
    ) {
        if (!current.enabled() || !current.roles().contains(com.firefly.security.FireflyRole.ADMIN)
                || (replacementEnabled && replacementRoles.contains(com.firefly.security.FireflyRole.ADMIN))) return;
        boolean anotherAdmin = repository.list().stream().anyMatch(user ->
                !user.username().equals(current.username()) && user.enabled()
                        && user.roles().contains(com.firefly.security.FireflyRole.ADMIN));
        if (!anotherAdmin) throw new IllegalArgumentException("the last enabled Admin account cannot be removed");
    }

    private String required(Map<String, String> request, String key) {
        String value = request.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        responses.respond(exchange, status, AdminHttpResponder.JSON, body);
    }
}
