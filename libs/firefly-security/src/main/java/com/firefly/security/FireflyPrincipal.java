package com.firefly.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Authenticated Admin user identity. */
public record FireflyPrincipal(
        String subject,
        Set<FireflyRole> roles,
        Instant issuedAt,
        Instant expiresAt,
        long identityVersion
) {
    public FireflyPrincipal {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (identityVersion < 0) throw new IllegalArgumentException("identityVersion must not be negative");
    }

    public boolean allows(FireflyRole required) {
        return roles.stream().anyMatch(role -> role.allows(required));
    }

}
