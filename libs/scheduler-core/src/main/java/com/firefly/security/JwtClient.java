package com.firefly.security;

import java.util.Objects;
import java.util.Set;

/** Client-credentials identity allowed to obtain short-lived access tokens. */
public record JwtClient(String clientId, String secret, Set<FireflyRole> roles, Set<String> executorNames) {
    public JwtClient {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId must not be blank");
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("client secret must not be blank");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        executorNames = Set.copyOf(Objects.requireNonNull(executorNames, "executorNames"));
        if (roles.isEmpty()) throw new IllegalArgumentException("client roles must not be empty");
    }
}
