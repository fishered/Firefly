package com.firefly.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void issuesAndVerifiesAdminUserIdentity() {
        Instant now = Instant.parse("2026-07-21T08:00:00Z");
        JwtService service = service(now);

        FireflyPrincipal principal = service.verify(service.issueUser(
                "admin", Set.of(FireflyRole.ADMIN), 3
        ));

        assertEquals("admin", principal.subject());
        assertEquals(3, principal.identityVersion());
        assertEquals(Set.of(FireflyRole.ADMIN), principal.roles());
    }

    @Test
    void rejectsExpiredAndTamperedTokens() {
        String token = service(Instant.parse("2026-07-21T08:00:00Z"))
                .issueUser("admin", Set.of(FireflyRole.ADMIN), 0);
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart)
                + replacement
                + token.substring(signatureStart + 1);

        assertThrows(IllegalArgumentException.class, () ->
                service(Instant.parse("2026-07-21T09:00:01Z")).verify(token));
        assertThrows(IllegalArgumentException.class, () ->
                service(Instant.parse("2026-07-21T08:00:00Z")).verify(tampered));
    }

    private JwtService service(Instant now) {
        return new JwtService(SECRET, "firefly", Duration.ofHours(1), Clock.fixed(now, ZoneOffset.UTC));
    }
}
