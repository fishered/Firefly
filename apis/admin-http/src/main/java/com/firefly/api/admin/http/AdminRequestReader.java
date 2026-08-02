package com.firefly.api.admin.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AdminRequestReader {
    private final AdminRequestLimits limits;
    private final ObjectMapper mapper;

    AdminRequestReader(AdminRequestLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(limits.maxJsonNestingDepth())
                .maxStringLength(limits.maxJsonStringLength())
                .build();
        this.mapper = new ObjectMapper(JsonFactory.builder().streamReadConstraints(constraints).build());
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.mapper.coercionConfigFor(LogicalType.Boolean)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    }

    void validate(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && query.length() > limits.maxQueryLength()) {
            throw new AdminHttpException(414, "uri_too_long", "query exceeds configured limit");
        }
        long contentLength = declaredContentLength(exchange);
        if (contentLength > limits.maxRequestBodyBytes()) {
            throw payloadTooLarge();
        }
    }

    String body(HttpExchange exchange) throws IOException {
        validate(exchange);
        byte[] bytes = exchange.getRequestBody().readNBytes(limits.maxRequestBodyBytes() + 1);
        if (bytes.length > limits.maxRequestBodyBytes()) {
            throw payloadTooLarge();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    Map<String, String> object(HttpExchange exchange) throws IOException {
        return parseObject(body(exchange));
    }

    Map<String, String> optionalObject(HttpExchange exchange) throws IOException {
        String body = body(exchange);
        if (body.isBlank()) {
            return Map.of();
        }
        return parseObject(body);
    }

    String queryParameter(HttpExchange exchange, String name) {
        validate(exchange);
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) return "";
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                return separator < 0 ? "" : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    List<String> ids(Map<String, String> request, String field) {
        String raw = request.get(field);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        raw = raw.trim();
        List<String> ids;
        if (raw.startsWith("[")) {
            try {
                JsonNode array = mapper.readTree(raw);
                if (!array.isArray()) throw new IllegalArgumentException(field + " must be an array");
                ids = new java.util.ArrayList<>();
                for (JsonNode value : array) {
                    if (!value.isTextual() || value.textValue().isBlank()) {
                        throw new IllegalArgumentException(field + " must contain non-blank string ids");
                    }
                    ids.add(value.textValue());
                }
                ids = ids.stream().distinct().toList();
            } catch (IOException | RuntimeException invalidArray) {
                if (invalidArray instanceof IllegalArgumentException iae) throw iae;
                throw new IllegalArgumentException(field + " must be a JSON string array", invalidArray);
            }
        } else {
            ids = List.of(raw);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        if (ids.size() > limits.maxBatchSize()) {
            throw new AdminHttpException(400, "batch_limit_exceeded",
                    field + " exceeds batch limit " + limits.maxBatchSize());
        }
        return ids;
    }

    <T> T typedObject(HttpExchange exchange, Class<T> type) throws IOException {
        String value = body(exchange);
        if (value.isBlank()) throw new IllegalArgumentException("request body must not be empty");
        try {
            T parsed = mapper.readValue(value, type);
            int batchSize = parsed instanceof BatchCancelRequest request
                    ? request.executionIds().size()
                    : parsed instanceof BatchRequeueRequest request
                    ? request.outboxIds().size() : 0;
            if (batchSize > limits.maxBatchSize()) {
                throw new AdminHttpException(400, "batch_limit_exceeded",
                        "request exceeds batch limit " + limits.maxBatchSize());
            }
            return parsed;
        } catch (AdminHttpException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalArgumentException("invalid request for " + type.getSimpleName(), failure);
        }
    }

    boolean booleanValue(Map<String, String> request, String field, boolean defaultValue) {
        String value = request.get(field);
        if (value == null) return defaultValue;
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }

    boolean requiredBoolean(Map<String, String> request, String field) {
        if (!request.containsKey(field)) throw new IllegalArgumentException("missing required field: " + field);
        return booleanValue(request, field, false);
    }

    private AdminHttpException payloadTooLarge() {
        return new AdminHttpException(413, "request_too_large",
                "request body exceeds " + limits.maxRequestBodyBytes() + " bytes");
    }

    private Map<String, String> parseObject(String body) {
        try {
            Map<String, Object> parsed = mapper.readValue(body, new TypeReference<>() { });
            Map<String, String> result = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
                    result.put(key, value == null ? "" : String.valueOf(value));
                    return;
                }
                try {
                    result.put(key, mapper.writeValueAsString(value));
                } catch (IOException e) {
                    throw new IllegalArgumentException("invalid JSON value for field: " + key, e);
                }
            });
            return result;
        } catch (IOException parseFailure) {
            throw new IllegalArgumentException("invalid JSON object", parseFailure);
        }
    }

    private long declaredContentLength(HttpExchange exchange) {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength == null || contentLength.isBlank()) return -1;
        try {
            long parsed = Long.parseLong(contentLength);
            if (parsed < 0) throw new NumberFormatException("negative Content-Length");
            return parsed;
        } catch (NumberFormatException invalidLength) {
            throw new AdminHttpException(400, "invalid_content_length", "invalid Content-Length header");
        }
    }
}
