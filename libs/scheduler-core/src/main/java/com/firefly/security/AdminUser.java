package com.firefly.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Persistent human identity used by the Admin console. */
public record AdminUser(
        String username,
        String passwordHash,
        Set<FireflyRole> roles,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public AdminUser {
        if (username == null || !username.matches("[A-Za-z0-9._@-]{1,128}")) {
            throw new IllegalArgumentException("username must contain 1-128 letters, digits, '.', '_', '@', or '-'");
        }
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("passwordHash must not be blank");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        if (roles.isEmpty() || roles.contains(FireflyRole.EXECUTOR)) {
            throw new IllegalArgumentException("Admin user roles must contain READER, OPERATOR, or ADMIN");
        }
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
