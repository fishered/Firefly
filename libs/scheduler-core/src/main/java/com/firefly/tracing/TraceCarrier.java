package com.firefly.tracing;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable W3C trace-context carrier persisted with an execution and sent over transports. */
public record TraceCarrier(Map<String, String> values) {
    public TraceCarrier {
        values = values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    public static TraceCarrier empty() {
        return new TraceCarrier(Map.of());
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
