package com.firefly.trigger;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.jupiter.api.Assertions.*;

class EventSignatureVerifierTest {
    @Test
    void acceptsCanonicalHmacAndRejectsStaleTimestamp() throws Exception {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        String canonical = "e1\norders.created\nk1\n" + now + "\n{}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        EventSignatureVerifier verifier = new EventSignatureVerifier("secret", Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
        assertTrue(verifier.verify("e1", "orders.created", "k1", "{}", now, signature));
        assertFalse(verifier.verify("e1", "orders.created", "k1", "{}", now.minus(Duration.ofMinutes(6)), signature));
    }
}
