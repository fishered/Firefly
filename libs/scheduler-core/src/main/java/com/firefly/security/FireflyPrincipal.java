package com.firefly.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Authenticated identity and its resource scope. */
public record FireflyPrincipal(
        String subject,
        Set<FireflyRole> roles,
        Set<String> executorNames,
        Instant issuedAt,
        Instant expiresAt,
        String identityType,
        long identityVersion
) {
    public FireflyPrincipal(
            String subject, Set<FireflyRole> roles, Set<String> executorNames,
            Instant issuedAt, Instant expiresAt
    ) {
        this(subject, roles, executorNames, issuedAt, expiresAt, "client", -1);
    }

    public FireflyPrincipal {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        executorNames = Set.copyOf(Objects.requireNonNull(executorNames, "executorNames"));
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!"client".equals(identityType) && !"user".equals(identityType)) {
            throw new IllegalArgumentException("identityType must be client or user");
        }
        if ("user".equals(identityType) && identityVersion < 0) {
            throw new IllegalArgumentException("user identityVersion must not be negative");
        }
    }

    public boolean allows(FireflyRole required) {
        return roles.stream().anyMatch(role -> role.allows(required));
    }

    public boolean allowsExecutor(String executorName) {
        return allows(FireflyRole.ADMIN)
                || (allows(FireflyRole.EXECUTOR)
                && (executorNames.contains("*") || executorNames.contains(executorName)));
    }

    public boolean humanUser() {
        return "user".equals(identityType);
    }
}
