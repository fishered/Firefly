package com.firefly.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable execution identifiers bounded by the JDBC schema contract. */
public final class ExecutionIds {
    public static final int MAX_LENGTH = 256;
    private static final int DIGEST_LENGTH = 64;

    private ExecutionIds() { }

    public static String child(String root, String qualifier) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(qualifier, "qualifier");
        if (root.isBlank() || qualifier.isBlank()) {
            throw new IllegalArgumentException("execution id components must not be blank");
        }
        return bounded(root + "@" + qualifier);
    }

    public static String bounded(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("execution id must not be blank");
        if (value.length() <= MAX_LENGTH) return value;
        String digest = sha256(value);
        return value.substring(0, MAX_LENGTH - DIGEST_LENGTH - 1) + "~" + digest;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
