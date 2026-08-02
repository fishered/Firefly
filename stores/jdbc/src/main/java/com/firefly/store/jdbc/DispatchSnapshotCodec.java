package com.firefly.store.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Versioned JSON envelope for immutable outbox job snapshots. */
final class DispatchSnapshotCodec {
    static final int CURRENT_VERSION = 1;

    private DispatchSnapshotCodec() {
    }

    static String encode(java.util.Map<String, String> fields) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                JdbcEncoding.encodeMap(fields).getBytes(StandardCharsets.UTF_8));
        return "{\"schemaVersion\":1,\"payload\":\"" + payload + "\"}";
    }

    static java.util.Map<String, String> decode(String value) {
        if (value == null || value.isBlank()) return java.util.Map.of();
        if (!value.trim().startsWith("{")) {
            return JdbcEncoding.decodeMap(value); // v0 compatibility
        }
        java.util.regex.Matcher version = java.util.regex.Pattern
                .compile("\\\"schemaVersion\\\"\\s*:\\s*(\\d+)").matcher(value);
        java.util.regex.Matcher payload = java.util.regex.Pattern
                .compile("\\\"payload\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]+)\\\"").matcher(value);
        if (!version.find() || !payload.find()) throw new JdbcException("invalid dispatch snapshot envelope");
        int schemaVersion = Integer.parseInt(version.group(1));
        if (schemaVersion != CURRENT_VERSION) throw new JdbcException("unsupported dispatch snapshot schemaVersion: " + schemaVersion);
        String encoded = new String(Base64.getUrlDecoder().decode(payload.group(1)), StandardCharsets.UTF_8);
        return JdbcEncoding.decodeMap(encoded);
    }
}
