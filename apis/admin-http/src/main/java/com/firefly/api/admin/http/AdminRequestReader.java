package com.firefly.api.admin.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        if (raw.startsWith("[") && raw.endsWith("]")) raw = raw.substring(1, raw.length() - 1);
        List<String> ids = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(value -> value.replaceAll("^\\\"|\\\"$", ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        if (ids.size() > limits.maxBatchSize()) {
            throw new AdminHttpException(400, "batch_limit_exceeded",
                    field + " exceeds batch limit " + limits.maxBatchSize());
        }
        return ids;
    }

    private AdminHttpException payloadTooLarge() {
        return new AdminHttpException(413, "request_too_large",
                "request body exceeds " + limits.maxRequestBodyBytes() + " bytes");
    }

    private Map<String, String> parseObject(String body) {
        try {
            Map<String, Object> parsed = mapper.readValue(body, new TypeReference<>() { });
            Map<String, String> result = new LinkedHashMap<>();
            parsed.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
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
