package com.firefly.executor.netty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/** JSON boundary for authenticated Gateway-to-Gateway HTTP messages. */
final class GatewayForwardingJsonCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    byte[] encode(ForwardRequest request) {
        return write(request);
    }

    byte[] encode(IsolateRequest request) {
        return write(request);
    }

    ForwardRequest decodeForward(byte[] body) throws IOException {
        return mapper.readValue(body, ForwardRequest.class);
    }

    IsolateRequest decodeIsolate(byte[] body) throws IOException {
        return mapper.readValue(body, IsolateRequest.class);
    }

    private byte[] write(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("failed to encode Gateway forwarding request", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ForwardRequest(String executorName, String instanceId, String sessionId, String frame) {
        boolean valid() {
            return present(executorName) && present(instanceId) && present(sessionId) && present(frame);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IsolateRequest(String executorName) {
        boolean valid() {
            return present(executorName);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
