package com.firefly.api.admin.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class AdminHttpResponder {
    static final String JSON = "application/json; charset=utf-8";

    void ok(HttpExchange exchange, String contentType, String body) throws IOException {
        respond(exchange, 200, contentType, body);
    }

    void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        exchange.setAttribute("firefly.response.status", status);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
