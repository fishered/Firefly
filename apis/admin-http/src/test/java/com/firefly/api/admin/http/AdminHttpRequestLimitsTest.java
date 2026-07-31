package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpRequestLimitsTest {
    @Test
    void rejectsOversizedBodiesQueriesJsonAndBatches() throws Exception {
        int port = freePort();
        AdminRequestLimits limits = new AdminRequestLimits(256, 32, 3, 64, 2);
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "", Map.of(), null, limits
        ));
        plugin.start(FireflyPluginContext.builder().build());
        try {
            HttpResponse<String> oversized = post(
                    port, "/api/schedules/preview", "{\"cron\":\"" + "x".repeat(300) + "\"}"
            );
            assertEquals(413, oversized.statusCode());
            assertTrue(oversized.body().contains("\"error\":\"request_too_large\""));

            HttpResponse<String> query = get(port, "/api/schedules/timezones?query=" + "x".repeat(40));
            assertEquals(414, query.statusCode());
            assertTrue(query.body().contains("\"error\":\"uri_too_long\""));

            HttpResponse<String> nested = post(
                    port, "/api/schedules/preview",
                    "{\"cron\":\"0 * * * * *\",\"a\":{\"b\":{\"c\":{\"d\":1}}}}"
            );
            assertEquals(400, nested.statusCode());
            assertTrue(nested.body().contains("\"error\":\"bad_request\""));

            HttpResponse<String> batch = post(
                    port, "/api/executions/batch-cancel", "{\"executionIds\":[\"a\",\"b\",\"c\"]}"
            );
            assertEquals(400, batch.statusCode());
            assertTrue(batch.body().contains("\"error\":\"batch_limit_exceeded\""));
        } finally {
            plugin.close();
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
