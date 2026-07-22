package com.firefly.server;

import com.firefly.security.JwtClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Shared JWT settings for HTTP APIs and Executor registration. */
public record JwtSecurityOptions(
        boolean enabled,
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Map<String, JwtClient> clients
) {
    public static final String DEVELOPMENT_SIGNING_SECRET =
            "firefly-local-development-signing-secret-unsafe-change-me";
    public static final String DEVELOPMENT_EXECUTOR_SECRET = "local-spring-secret";

    public JwtSecurityOptions {
        secret = secret == null ? "" : secret;
        issuer = issuer == null ? "" : issuer.trim();
        Objects.requireNonNull(accessTokenTtl, "accessTokenTtl");
        clients = Map.copyOf(Objects.requireNonNull(clients, "clients"));
        if (enabled) {
            if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("firefly.security.jwt.secret must contain at least 32 UTF-8 bytes");
            }
            if (issuer.isBlank()) throw new IllegalArgumentException("firefly.security.jwt.issuer is required");
            if (accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
                throw new IllegalArgumentException("firefly.security.jwt.access-token-ttl must be positive");
            }
        }
    }

    public static JwtSecurityOptions disabled() {
        return new JwtSecurityOptions(false, "", "firefly", Duration.ofHours(1), Map.of());
    }

    public boolean usesDevelopmentCredentials() {
        if (!enabled) return false;
        if (DEVELOPMENT_SIGNING_SECRET.equals(secret)) return true;
        return clients.values().stream().anyMatch(client -> DEVELOPMENT_EXECUTOR_SECRET.equals(client.secret()));
    }
}
