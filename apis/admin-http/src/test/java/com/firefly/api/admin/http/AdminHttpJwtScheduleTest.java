package com.firefly.api.admin.http;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.security.FireflyRole;
import com.firefly.security.JwtClient;
import com.firefly.security.JwtService;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpJwtScheduleTest {
    @Test
    void exchangesCredentialsEnforcesExecutorScopeAndPreviewsSchedules() throws Exception {
        int port = freePort();
        JwtService jwt = new JwtService("01234567890123456789012345678901", "firefly",
                Duration.ofHours(1), Clock.systemUTC());
        JwtClient admin = new JwtClient("admin", "admin-secret", Set.of(FireflyRole.ADMIN), Set.of("*"));
        JwtClient billing = new JwtClient("billing", "billing-secret", Set.of(FireflyRole.EXECUTOR),
                Set.of("billing-executor"));
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        catalog.saveExecutor(executor("billing-executor"));
        catalog.saveExecutor(executor("orders-executor"));
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "", Map.of(), jwt,
                Map.of("admin", admin, "billing", billing)
        ));
        plugin.start(FireflyPluginContext.builder()
                .jobRepository(new InMemoryJobRepository()).schedulerCatalog(catalog).build());
        try {
            String billingToken = token(port, "billing", "billing-secret");
            assertEquals(201, request(port, "/api/jobs", "POST", billingToken,
                    job("billing-job", "billing-executor")).statusCode());
            assertEquals(403, request(port, "/api/jobs", "POST", billingToken,
                    job("orders-job", "orders-executor")).statusCode());
            assertEquals(403, request(port, "/api/jobs/billing-job/trigger", "POST", billingToken, "")
                    .statusCode());

            String adminToken = token(port, "admin", "admin-secret");
            assertEquals(201, request(port, "/api/jobs", "POST", adminToken,
                    job("orders-job", "orders-executor")).statusCode());
            assertEquals(403, request(port, "/api/jobs/orders-job", "PUT", billingToken,
                    "{\"executorName\":\"billing-executor\"}").statusCode());
            HttpResponse<String> preview = request(port, "/api/schedules/preview", "POST", adminToken,
                    "{\"cron\":\"0 */5 * * * *\",\"zoneId\":\"Asia/Shanghai\",\"count\":3}");
            assertEquals(200, preview.statusCode());
            assertTrue(preview.body().contains("\"nextFireTimes\":"));
            HttpResponse<String> fuzzyZones = request(
                    port, "/api/schedules/timezones?query=shhai", "GET", adminToken, ""
            );
            assertEquals(200, fuzzyZones.statusCode());
            assertTrue(fuzzyZones.body().contains("Asia/Shanghai"));
            HttpResponse<String> preferredZones = request(
                    port, "/api/schedules/timezones?query=", "GET", adminToken, ""
            );
            assertEquals(200, preferredZones.statusCode());
            assertTrue(preferredZones.body().startsWith("{\"timezones\":[\"UTC\",\"Asia/Shanghai\""));
            assertEquals(401, request(port, "/api/overview", "GET", "bad-token", "").statusCode());
        } finally {
            plugin.close();
        }
    }

    private ExecutorDefinition executor(String name) {
        return ExecutorDefinition.builder().name(name).protocols(Set.of(ExecutorProtocol.TCP)).build();
    }

    private String job(String id, String executor) {
        return "{\"id\":\"" + id + "\",\"executorName\":\"" + executor
                + "\",\"handlerName\":\"run\",\"cron\":\"0 * * * * *\"}";
    }

    private String token(int port, String clientId, String secret) throws Exception {
        HttpResponse<String> response = request(port, "/api/auth/token", "POST", "",
                "{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + secret + "\"}");
        assertEquals(200, response.statusCode());
        return AdminHttpJson.object(response.body()).get("accessToken");
    }

    private HttpResponse<String> request(int port, String path, String method, String token, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        if ("GET".equals(method)) request.GET();
        else request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
