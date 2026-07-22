package com.firefly.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void issuesAndVerifiesRolesAndExecutorScope() {
        Instant now = Instant.parse("2026-07-21T08:00:00Z");
        JwtService service = service(now);

        FireflyPrincipal principal = service.verify(service.issue(new JwtClient(
                "billing", "client-secret", Set.of(FireflyRole.EXECUTOR), Set.of("billing-executor")
        )));

        assertEquals("billing", principal.subject());
        assertTrue(principal.allowsExecutor("billing-executor"));
        assertEquals(Set.of(FireflyRole.EXECUTOR), principal.roles());
    }

    @Test
    void rejectsExpiredAndTamperedTokens() {
        String token = service(Instant.parse("2026-07-21T08:00:00Z")).issue(new JwtClient(
                "admin", "client-secret", Set.of(FireflyRole.ADMIN), Set.of("*")
        ));

        assertThrows(IllegalArgumentException.class, () ->
                service(Instant.parse("2026-07-21T09:00:01Z")).verify(token));
        assertThrows(IllegalArgumentException.class, () ->
                service(Instant.parse("2026-07-21T08:00:00Z")).verify(token.substring(0, token.length() - 1) + "A"));
    }

    private JwtService service(Instant now) {
        return new JwtService(SECRET, "firefly", Duration.ofHours(1), Clock.fixed(now, ZoneOffset.UTC));
    }
}
