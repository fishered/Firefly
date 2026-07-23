package com.firefly.api.admin.http;

import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.security.AdminUser;
import com.firefly.security.FireflyRole;
import com.firefly.security.InMemoryAdminUserRepository;
import com.firefly.security.InMemoryIntegrationKeyRepository;
import com.firefly.security.IntegrationKeyService;
import com.firefly.security.JwtService;
import com.firefly.security.Pbkdf2PasswordHasher;
import com.firefly.store.InMemoryJobRepository;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpJwtScheduleTest {
    @Test
    void separatesAdminSessionsFromRestrictedIntegrationKeyAccess() throws Exception {
        int port = freePort();
        JwtService jwt = new JwtService("01234567890123456789012345678901", "firefly",
                Duration.ofHours(1), Clock.systemUTC());
        Instant now = Instant.now();
        InMemoryAdminUserRepository users = new InMemoryAdminUserRepository();
        users.create(new AdminUser(
                "admin", new Pbkdf2PasswordHasher().hash("admin-secret".toCharArray()),
                Set.of(FireflyRole.ADMIN), true, 0, now, now
        ));
        InMemoryIntegrationKeyRepository integrationKeys = new InMemoryIntegrationKeyRepository();
        String integrationKey = new IntegrationKeyService(integrationKeys, Clock.systemUTC()).rotate().plaintext();
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        catalog.saveExecutor(executor("billing-executor"));
        catalog.saveExecutor(executor("orders-executor"));
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "", Map.of(), jwt
        ));
        plugin.start(FireflyPluginContext.builder()
                .jobRepository(new InMemoryJobRepository()).schedulerCatalog(catalog)
                .adminUserRepository(users).integrationKeyRepository(integrationKeys).build());
        try {
            assertEquals(201, request(port, "/api/jobs", "POST", integrationKey,
                    job("billing-job", "billing-executor")).statusCode());
            assertEquals(201, request(port, "/api/jobs", "POST", integrationKey,
                    job("orders-job", "orders-executor")).statusCode());
            assertEquals(403, request(port, "/api/jobs/billing-job/trigger", "POST", integrationKey, "")
                    .statusCode());
            assertEquals(403, request(port, "/api/users", "GET", integrationKey, "").statusCode());

            String adminToken = login(port, "admin", "admin-secret");
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

    private String login(int port, String username, String password) throws Exception {
        HttpResponse<String> response = request(port, "/api/auth/login", "POST", "",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, response.statusCode());
        return AdminHttpJson.object(response.body()).get("accessToken");
    }

    private HttpResponse<String> request(int port, String path, String method, String token, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token.startsWith("ffk_")) request.header("X-Firefly-Integration-Key", token);
        else if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
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
