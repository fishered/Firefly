package com.firefly.api.admin.http;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.cluster.InMemoryNodeRegistry;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminHttpRbacTest {
    @Test
    void separatesReadOperateAndAdminMutations() throws Exception {
        int port = freePort();
        InMemoryJobRepository jobs = new InMemoryJobRepository();
        jobs.save(JobDefinition.builder().id("job-1").name("Job 1").handlerName("run")
                .schedule(new CronSchedule("0 * * * * *")).build(), Instant.now());
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "legacy-admin",
                Map.of("reader", AdminRole.READER, "operator", AdminRole.OPERATOR, "admin", AdminRole.ADMIN)
        ));
        plugin.start(FireflyPluginContext.builder()
                .jobRepository(jobs)
                .nodeRegistry(new InMemoryNodeRegistry())
                .schedulerCatalog(new InMemorySchedulerCatalog())
                .pluginStatusProvider(() -> java.util.List.of(new com.firefly.plugin.FireflyPluginDescriptor(
                        "metrics-prometheus", "Prometheus Metrics", "1.0.0", "Metrics endpoint",
                        "com.firefly.MetricsPlugin", "CLASSPATH", "ACTIVE"
                )))
                .build());
        try {
            assertEquals(200, request(port, "/api/overview", "GET", "reader", "").statusCode());
            HttpResponse<String> plugins = request(port, "/api/plugins", "GET", "reader", "");
            assertEquals(200, plugins.statusCode());
            org.junit.jupiter.api.Assertions.assertTrue(plugins.body().contains("metrics-prometheus"));
            assertEquals(403, request(port, "/api/jobs/job-1", "PATCH", "reader", "{\"enabled\":false}").statusCode());
            assertEquals(200, request(port, "/api/jobs/job-1", "PATCH", "operator", "{\"enabled\":false}").statusCode());
            assertEquals(403, request(port, "/api/executor-definitions", "POST", "operator",
                    "{\"name\":\"orders\"}").statusCode());
            assertEquals(201, request(port, "/api/executor-definitions", "POST", "admin",
                    "{\"name\":\"orders\"}").statusCode());
            assertEquals(403, request(port, "/api/executor-definitions/orders", "DELETE", "operator", "")
                    .statusCode());
            assertEquals(200, request(port, "/api/executor-definitions/orders", "DELETE", "admin", "")
                    .statusCode());
            assertEquals(401, request(port, "/api/overview", "GET", "wrong", "").statusCode());
            assertEquals(200, request(port, "/api/health", "GET", "", "").statusCode());
        } finally {
            plugin.close();
        }
    }

    private HttpResponse<String> request(
            int port, String path, String method, String token, String body
    ) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Firefly-Token", token);
        if ("GET".equals(method)) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
