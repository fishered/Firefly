package com.firefly.api.admin.http;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.trigger.InMemoryBackfillOperationStore;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpBackfillTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void previewsRunsCanaryAndPromotesDurableBackfill() throws Exception {
        int port = freePort();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryJobRepository jobs = new InMemoryJobRepository(clock);
        jobs.save(JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build(), NOW.plusSeconds(60));
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder().clock(clock).jobRepository(jobs)
                .backfillOperationStore(new InMemoryBackfillOperationStore()).build());
        try {
            String request = """
                    {"requestId":"run","jobId":"job","fromInclusive":"2026-09-28T00:00:00Z",
                     "toInclusive":"2026-09-28T00:03:00Z","maxExecutions":10,"batchSize":2,
                     "canaryPercent":50,"rootExecutionId":"root"}
                    """;
            HttpResponse<String> preview = post(port, "/api/backfills/preview", request);
            assertEquals(200, preview.statusCode());
            assertTrue(preview.body().contains("\"expanded\":4"));
            assertTrue(preview.body().contains("\"canaryExecutions\":2"));

            assertEquals(202, post(port, "/api/backfills", request).statusCode());
            String paused = awaitStatus(port, "run", "PAUSED");
            assertTrue(paused.contains("\"dispatched\":2"));
            assertTrue(paused.contains("\"remaining\":0"));

            assertEquals(202, post(port, "/api/backfills/run/promote", "{}").statusCode());
            String completed = awaitStatus(port, "run", "COMPLETED");
            assertTrue(completed.contains("\"dispatched\":4"));
            assertTrue(get(port, "/api/backfills").body().contains("\"requestId\":\"run\""));
        } finally {
            plugin.close();
        }
    }

    private String awaitStatus(int port, String requestId, String status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String body = "";
        while (System.nanoTime() < deadline) {
            body = get(port, "/api/backfills/" + requestId).body();
            if (body.contains("\"status\":\"" + status + "\"")) return body;
            Thread.sleep(25);
        }
        throw new AssertionError("backfill did not reach " + status + ": " + body);
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
