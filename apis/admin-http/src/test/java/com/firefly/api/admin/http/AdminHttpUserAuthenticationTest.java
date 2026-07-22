package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import com.firefly.security.AdminUser;
import com.firefly.security.FireflyRole;
import com.firefly.security.InMemoryAdminUserRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpUserAuthenticationTest {
    @Test
    void authenticatesPersistentUsersAndProtectsUserAdministration() throws Exception {
        int port = freePort();
        Instant now = Instant.now();
        InMemoryAdminUserRepository users = new InMemoryAdminUserRepository();
        users.create(new AdminUser(
                "admin", new Pbkdf2PasswordHasher().hash("correct-password".toCharArray()),
                Set.of(FireflyRole.ADMIN), true, 0, now, now
        ));
        JwtService jwt = new JwtService("01234567890123456789012345678901", "firefly",
                Duration.ofHours(1), Clock.systemUTC());
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "", Map.of(), jwt, Map.of()
        ));
        plugin.start(FireflyPluginContext.builder()
                .jobRepository(new InMemoryJobRepository())
                .adminUserRepository(users)
                .build());
        try {
            assertEquals(401, request(port, "/api/auth/login", "POST", "",
                    "{\"username\":\"admin\",\"password\":\"wrong-password\"}").statusCode());
            String token = login(port, "admin", "correct-password");

            HttpResponse<String> created = request(port, "/api/users", "POST", token,
                    "{\"username\":\"operator\",\"password\":\"operator-password\","
                            + "\"roles\":\"OPERATOR\"}");
            assertEquals(201, created.statusCode());
            assertFalse(created.body().contains("passwordHash"));
            assertEquals(409, request(port, "/api/users", "POST", token,
                    "{\"username\":\"operator\",\"password\":\"operator-password\","
                            + "\"roles\":\"OPERATOR\"}").statusCode());
            String operatorToken = login(port, "operator", "operator-password");

            HttpResponse<String> listed = request(port, "/api/users", "GET", token, "");
            assertEquals(200, listed.statusCode());
            assertTrue(listed.body().contains("\"username\":\"admin\""));
            assertTrue(listed.body().contains("\"username\":\"operator\""));
            assertFalse(listed.body().contains("password_hash"));

            assertEquals(400, request(port, "/api/users/admin", "PUT", token,
                    "{\"version\":0,\"enabled\":false}").statusCode());
            assertEquals(200, request(port, "/api/users/operator", "PUT", token,
                    "{\"version\":0,\"enabled\":false}").statusCode());
            assertEquals(401, request(port, "/api/overview", "GET", operatorToken, "").statusCode());
            assertEquals(200, request(port, "/api/users/operator", "PUT", token,
                    "{\"version\":1,\"enabled\":true,\"roles\":\"READER\"}").statusCode());
            assertEquals(409, request(port, "/api/users/operator", "PUT", token,
                    "{\"version\":0,\"roles\":\"OPERATOR\"}").statusCode());
            assertEquals(200, request(port, "/api/users/operator", "DELETE", token,
                    "{\"version\":2}").statusCode());
            assertEquals(400, request(port, "/api/users/admin", "DELETE", token,
                    "{\"version\":0}").statusCode());

            assertEquals(401, request(port, "/api/auth/token", "POST", "",
                    "{\"clientId\":\"admin\",\"clientSecret\":\"correct-password\"}").statusCode());
        } finally {
            plugin.close();
        }
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
