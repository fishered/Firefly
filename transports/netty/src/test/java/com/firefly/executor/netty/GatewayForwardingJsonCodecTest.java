package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayForwardingJsonCodecTest {
    private final GatewayForwardingJsonCodec codec = new GatewayForwardingJsonCodec();

    @Test
    void roundTripsTypedForwardRequest() throws Exception {
        var expected = new GatewayForwardingJsonCodec.ForwardRequest(
                "orders", "instance-1", "session-1", "{\"type\":\"TRIGGER_JOB\"}"
        );

        var actual = codec.decodeForward(codec.encode(expected));

        assertEquals(expected, actual);
        assertTrue(actual.valid());
    }

    @Test
    void ignoresFutureFieldsAndRejectsMissingRequiredValues() throws Exception {
        var request = codec.decodeIsolate(
                "{\"executorName\":\"orders\",\"futureField\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        var invalid = codec.decodeIsolate("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals("orders", request.executorName());
        assertTrue(request.valid());
        assertFalse(invalid.valid());
    }
}
