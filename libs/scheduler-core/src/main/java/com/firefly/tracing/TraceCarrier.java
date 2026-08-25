package com.firefly.tracing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable W3C trace-context carrier persisted with an execution and sent over transports. */
public record TraceCarrier(Map<String, String> values) {
    public static final int MAX_VALUE_LENGTH = 4096;
    public static final int MAX_TOTAL_LENGTH = 8192;

    public TraceCarrier {
        values = sanitize(values);
    }

    public static TraceCarrier empty() {
        return new TraceCarrier(Map.of());
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Keeps only W3C propagation fields and bounds data persisted in outbox snapshots. */
    public static Map<String, String> sanitize(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        int totalLength = 0;
        for (String key : List.of("traceparent", "tracestate", "baggage")) {
            String value = values.get(key);
            if (value == null) continue;
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("trace carrier value exceeds " + MAX_VALUE_LENGTH + " characters: " + key);
            }
            totalLength += key.length() + value.length();
            if (totalLength > MAX_TOTAL_LENGTH) {
                throw new IllegalArgumentException("trace carrier exceeds " + MAX_TOTAL_LENGTH + " characters");
            }
            safe.put(key, value);
        }
        return Map.copyOf(safe);
    }
}
