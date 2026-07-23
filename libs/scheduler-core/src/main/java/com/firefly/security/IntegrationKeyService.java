package com.firefly.security;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Generates, rotates, and verifies the single key used by trusted service integrations. */
public final class IntegrationKeyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32;
    private static final int MAX_ROTATION_RETRIES = 16;

    private final IntegrationKeyRepository repository;
    private final Pbkdf2PasswordHasher hasher;
    private final Clock clock;
    private volatile VerifiedKey verifiedKey;

    public IntegrationKeyService(IntegrationKeyRepository repository, Clock clock) {
        this(repository, new Pbkdf2PasswordHasher(), clock);
    }

    IntegrationKeyService(
            IntegrationKeyRepository repository,
            Pbkdf2PasswordHasher hasher,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean verify(String provided) {
        if (provided == null || provided.isBlank()) return false;
        IntegrationKeyRecord record = repository.find().orElse(null);
        if (record == null) return false;
        byte[] fingerprint = fingerprint(provided);
        VerifiedKey cached = verifiedKey;
        if (cached != null && cached.version() == record.version()
                && MessageDigest.isEqual(cached.fingerprint(), fingerprint)) {
            return true;
        }
        char[] value = provided.toCharArray();
        try {
            boolean verified = hasher.verify(value, record.keyHash());
            if (verified) verifiedKey = new VerifiedKey(record.version(), fingerprint);
            return verified;
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    public RotatedIntegrationKey rotate() {
        for (int attempt = 0; attempt < MAX_ROTATION_RETRIES; attempt++) {
            String plaintext = generate();
            char[] value = plaintext.toCharArray();
            String hash;
            try {
                hash = hasher.hash(value);
            } finally {
                Arrays.fill(value, '\0');
            }
            Instant now = clock.instant();
            IntegrationKeyRecord current = repository.find().orElse(null);
            long version = current == null ? 1 : current.version() + 1;
            Instant createdAt = current == null ? now : current.createdAt();
            IntegrationKeyRecord replacement = new IntegrationKeyRecord(hash, version, createdAt, now);
            boolean stored = current == null
                    ? repository.create(replacement)
                    : repository.update(replacement, current.version());
            if (stored) {
                verifiedKey = new VerifiedKey(version, fingerprint(plaintext));
                return new RotatedIntegrationKey(plaintext, version, now);
            }
        }
        throw new IllegalStateException("integration key was rotated concurrently too many times");
    }

    private String generate() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return "ffk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] fingerprint(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record VerifiedKey(long version, byte[] fingerprint) { }

    public record RotatedIntegrationKey(String plaintext, long version, Instant updatedAt) { }
}
