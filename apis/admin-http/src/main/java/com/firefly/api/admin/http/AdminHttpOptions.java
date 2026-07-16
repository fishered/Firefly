package com.firefly.api.admin.http;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for the lightweight admin web plugin.
 */
public record AdminHttpOptions(
        String host,
        int port,
        Duration heartbeatTimeout,
        String apiToken,
        java.util.Map<String, AdminRole> tokenRoles
) {
    public AdminHttpOptions(String host, int port, Duration heartbeatTimeout) {
        this(host, port, heartbeatTimeout, "", java.util.Map.of());
    }

    public AdminHttpOptions(String host, int port, Duration heartbeatTimeout, String apiToken) {
        this(host, port, heartbeatTimeout, apiToken, java.util.Map.of());
    }

    public AdminHttpOptions {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        apiToken = apiToken == null ? "" : apiToken.trim();
        java.util.HashMap<String, AdminRole> roles = new java.util.HashMap<>();
        if (tokenRoles != null) {
            tokenRoles.forEach((token, role) -> {
                if (token != null && !token.isBlank() && role != null) roles.put(token.trim(), role);
            });
        }
        if (!apiToken.isBlank()) roles.merge(apiToken, AdminRole.ADMIN,
                (left, right) -> left.ordinal() >= right.ordinal() ? left : right);
        tokenRoles = java.util.Map.copyOf(roles);
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
            throw new IllegalArgumentException("heartbeatTimeout must be positive");
        }
    }

    public static AdminHttpOptions defaults() {
        return new AdminHttpOptions("127.0.0.1", 9710, Duration.ofSeconds(30), "", java.util.Map.of());
    }
}
