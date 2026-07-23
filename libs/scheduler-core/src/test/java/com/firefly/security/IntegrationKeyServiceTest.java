package com.firefly.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationKeyServiceTest {
    @Test
    void rotatesAndVerifiesOnlyTheLatestPlaintextKey() {
        InMemoryIntegrationKeyRepository repository = new InMemoryIntegrationKeyRepository();
        IntegrationKeyService service = new IntegrationKeyService(
                repository, Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC)
        );

        assertFalse(service.verify("missing"));
        IntegrationKeyService.RotatedIntegrationKey first = service.rotate();
        assertTrue(first.plaintext().startsWith("ffk_"));
        assertTrue(service.verify(first.plaintext()));
        assertFalse(repository.find().orElseThrow().keyHash().contains(first.plaintext()));

        IntegrationKeyService secondNode = new IntegrationKeyService(repository, Clock.systemUTC());
        IntegrationKeyService.RotatedIntegrationKey second = secondNode.rotate();
        assertNotEquals(first.plaintext(), second.plaintext());
        assertFalse(service.verify(first.plaintext()));
        assertTrue(service.verify(second.plaintext()));
        assertTrue(second.version() > first.version());
    }
}
