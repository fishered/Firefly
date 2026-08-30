package com.firefly.trigger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** Verifies signed event envelopes and rejects stale/replayed timestamps. */
public final class EventSignatureVerifier {
    private final byte[] secret;
    private final Clock clock;
    private final Duration maxSkew;

    public EventSignatureVerifier(String secret, Clock clock, Duration maxSkew) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("event signing secret is required");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxSkew = Objects.requireNonNull(maxSkew, "maxSkew");
        if (maxSkew.isNegative() || maxSkew.isZero()) throw new IllegalArgumentException("maxSkew must be positive");
    }

    public boolean verify(String eventId, String eventType, String idempotencyKey, String payload,
                          Instant signedAt, String signature) {
        if (signedAt == null || signature == null || signature.isBlank()) return false;
        if (Math.abs(Duration.between(clock.instant(), signedAt).toMillis()) > maxSkew.toMillis()) return false;
        String canonical = eventId + "\n" + eventType + "\n" + idempotencyKey + "\n"
                + signedAt.toString() + "\n" + Objects.requireNonNullElse(payload, "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getEncoder().encodeToString(expected);
            String hex = java.util.HexFormat.of().formatHex(expected);
            return MessageDigest.isEqual(encoded.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))
                    || MessageDigest.isEqual(hex.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("unable to verify event signature", e);
        }
    }
}
