package com.firefly.api.admin.http;

import com.firefly.domain.CronSchedule;
import com.firefly.plugin.FireflyPluginContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class AdminScheduleController {
    private static final List<String> PREFERRED_TIMEZONES = List.of(
            "UTC", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Tokyo", "Asia/Singapore",
            "Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles"
    );

    private final FireflyPluginContext context;
    private final AdminRequestReader requests;
    private final AdminHttpResponder responses;

    AdminScheduleController(
            FireflyPluginContext context,
            AdminRequestReader requests,
            AdminHttpResponder responses
    ) {
        this.context = java.util.Objects.requireNonNull(context, "context");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.responses = java.util.Objects.requireNonNull(responses, "responses");
    }

    void preview(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, String> request = requests.object(exchange);
        String expression = required(request, "cron");
        ZoneId zoneId = ZoneId.of(request.getOrDefault("zoneId", "UTC"));
        int count = Math.max(1, Math.min(20, Integer.parseInt(request.getOrDefault("count", "5"))));
        CronSchedule schedule = new CronSchedule(expression);
        Instant cursor = context.clock().instant();
        StringBuilder json = new StringBuilder("{\"cron\":\"").append(responses.escape(expression))
                .append("\",\"zoneId\":\"").append(responses.escape(zoneId.getId()))
                .append("\",\"nextFireTimes\":[");
        for (int i = 0; i < count; i++) {
            cursor = schedule.nextAfter(cursor, zoneId);
            if (i > 0) json.append(',');
            json.append("{\"instant\":\"").append(cursor).append("\",\"local\":\"")
                    .append(cursor.atZone(zoneId).toLocalDateTime()).append("\"}");
        }
        respond(exchange, 200, json.append("]}").toString());
    }

    void timezones(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        String query = requests.queryParameter(exchange, "query")
                .trim().toLowerCase(java.util.Locale.ROOT);
        List<String> zones = ZoneId.getAvailableZoneIds().stream()
                .filter(zone -> timezoneScore(zone, query) < Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt((String zone) -> timezoneScore(zone, query))
                        .thenComparing(java.util.function.Function.identity()))
                .limit(100)
                .toList();
        String body = zones.stream().map(zone -> "\"" + responses.escape(zone) + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{\"timezones\":[", "]}"));
        respond(exchange, 200, body);
    }

    private int timezoneScore(String zone, String query) {
        if (query.isBlank()) {
            int index = PREFERRED_TIMEZONES.indexOf(zone);
            return index < 0 ? 100 : index;
        }
        String candidate = zone.toLowerCase(java.util.Locale.ROOT);
        if (candidate.equals(query)) return 0;
        if (candidate.startsWith(query)) return 1;
        if (java.util.Arrays.stream(candidate.split("[/_-]")).anyMatch(part -> part.startsWith(query))) return 2;
        if (candidate.contains(query)) return 3;
        return fuzzySubsequence(normalizeTimezone(candidate), normalizeTimezone(query)) ? 4 : Integer.MAX_VALUE;
    }

    private String normalizeTimezone(String value) {
        return value.replace("/", "").replace("_", "").replace("-", "").replace(" ", "");
    }

    private boolean fuzzySubsequence(String candidate, String query) {
        int queryIndex = 0;
        for (int index = 0; index < candidate.length() && queryIndex < query.length(); index++) {
            if (candidate.charAt(index) == query.charAt(queryIndex)) queryIndex++;
        }
        return queryIndex == query.length();
    }

    private String required(Map<String, String> request, String key) {
        String value = request.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field: " + key);
        }
        return value;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        responses.respond(exchange, status, AdminHttpResponder.JSON, body);
    }
}
