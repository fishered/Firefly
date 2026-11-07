package com.firefly.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable bounded reservation keys for resource units that outlive one process. */
public final class TenantReservationKeys {
    private TenantReservationKeys() { }

    public static String backlog(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        return executionId;
    }

    public static String running(String executionId) {
        return hashed("running", executionId);
    }

    public static String trigger(String executionId) {
        return hashed("trigger", executionId);
    }

    private static String hashed(String kind, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("reservation source must not be blank");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return kind + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
