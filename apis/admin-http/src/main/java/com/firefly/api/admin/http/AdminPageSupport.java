package com.firefly.api.admin.http;

import com.firefly.query.CursorPage;
import com.firefly.query.PageCursor;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

final class AdminPageSupport {
    private AdminPageSupport() { }

    static int limit(AdminRequestReader requests, HttpExchange exchange, int defaultValue) {
        String raw = requests.queryParameter(exchange, "limit");
        if (raw.isBlank()) return defaultValue;
        int value = Integer.parseInt(raw);
        if (value < 1 || value > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        return value;
    }

    static String text(AdminRequestReader requests, HttpExchange exchange, String name) {
        return requests.queryParameter(exchange, name).trim();
    }

    static Instant instant(AdminRequestReader requests, HttpExchange exchange, String name) {
        String raw = text(requests, exchange, name);
        return raw.isBlank() ? null : Instant.parse(raw);
    }

    static PageCursor cursor(AdminRequestReader requests, HttpExchange exchange) {
        String raw = text(requests, exchange, "cursor");
        if (raw.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('\n');
            if (separator < 1 || separator == decoded.length() - 1) throw new IllegalArgumentException();
            return new PageCursor(Instant.parse(decoded.substring(0, separator)), decoded.substring(separator + 1));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    static String json(String listJson, CursorPage<?> page) {
        if (!listJson.endsWith("}")) throw new IllegalArgumentException("page JSON must be an object");
        String next = page.nextCursor() == null ? "null" : "\"" + encode(page.nextCursor()) + "\"";
        return listJson.substring(0, listJson.length() - 1)
                + ",\"nextCursor\":" + next + ",\"hasMore\":" + page.hasMore() + "}";
    }

    private static String encode(PageCursor cursor) {
        String value = cursor.timestamp() + "\n" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
