package com.firefly.tracing;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TraceCarrierTest {
    @Test
    void keepsOnlyW3cPropagationFields() {
        TraceCarrier carrier = new TraceCarrier(Map.of(
                "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "baggage", "tenant=demo",
                "authorization", "secret"
        ));

        assertEquals(Map.of("traceparent", carrier.values().get("traceparent"), "baggage", "tenant=demo"), carrier.values());
    }

    @Test
    void rejectsOversizedPropagationValues() {
        assertThrows(IllegalArgumentException.class, () -> new TraceCarrier(Map.of(
                "traceparent", "x".repeat(TraceCarrier.MAX_VALUE_LENGTH + 1)
        )));
    }
}
