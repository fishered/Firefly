package com.firefly.api.admin.http;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpJobRegistrationTest {
    @Test
    void supportsLookupConflictDetectionAndJobUpdate() throws Exception {
        int port = freePort();
        InMemoryJobRepository jobs = new InMemoryJobRepository();
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        catalog.saveExecutor(ExecutorDefinition.builder()
                .name("billing-executor")
                .protocols(Set.of(ExecutorProtocol.TCP))
                .build());
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder()
                .jobRepository(jobs)
                .schedulerCatalog(catalog)
                .build());
        String createBody = """
                {"id":"billing-daily","name":"Daily billing","groupId":"billing",
                 "executorName":"billing-executor","handlerName":"billingHandler",
                 "cron":"0 0 2 * * *","zoneId":"Asia/Shanghai","enabled":false}
                """;
        try {
            assertEquals(404, get(port, "/api/jobs/billing-daily").statusCode());
            assertEquals(201, request(port, "/api/jobs", "POST", createBody).statusCode());

            HttpResponse<String> found = get(port, "/api/jobs/billing-daily");
            assertEquals(200, found.statusCode());
            assertTrue(found.body().contains("\"id\":\"billing-daily\""));
            assertTrue(found.body().contains("\"groupId\":\"billing\""));
            assertTrue(found.body().contains("\"enabled\":false"));
            assertEquals(409, request(port, "/api/jobs", "POST", createBody).statusCode());

            HttpResponse<String> updated = request(
                    port,
                    "/api/jobs/billing-daily",
                    "PUT",
                    "{\"cron\":\"0 */10 * * * *\",\"enabled\":true}"
            );
            assertEquals(200, updated.statusCode());
            assertEquals("0 */10 * * * *", jobs.find("billing-daily").orElseThrow().definition().schedule().toString());
            assertTrue(jobs.find("billing-daily").orElseThrow().definition().enabled());
        } finally {
            plugin.close();
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return request(port, path, "GET", "");
    }

    private HttpResponse<String> request(int port, String path, String method, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if ("GET".equals(method)) {
            request.GET();
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
