package com.firefly.server;

import java.time.Duration;
import java.util.Objects;

/** JWT settings for human Admin console sessions. */
public record JwtSecurityOptions(
        boolean enabled,
        String secret,
        String issuer,
        Duration accessTokenTtl
) {
    public static final String DEVELOPMENT_SIGNING_SECRET =
            "firefly-local-development-signing-secret-unsafe-change-me";
    public JwtSecurityOptions {
        secret = secret == null ? "" : secret;
        issuer = issuer == null ? "" : issuer.trim();
        Objects.requireNonNull(accessTokenTtl, "accessTokenTtl");
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
        return new JwtSecurityOptions(false, "", "firefly", Duration.ofHours(1));
    }

    public boolean usesDevelopmentCredentials() {
        if (!enabled) return false;
        return DEVELOPMENT_SIGNING_SECRET.equals(secret);
    }
}
