package com.firefly.spring.job;

import com.firefly.executor.netty.NettyExecutorClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireflyJobRegistrarTest {
    @Test
    void createsMissingDeclaredJobWithExecutorAndIntegrationKey() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 404, "{\"error\":\"job_not_found\"}");
                return;
            }
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-Firefly-Integration-Key"));
            respond(exchange, 201, "{\"status\":\"created\"}");
        });
        NettyExecutorClient client = client();
        try {
            FireflyJobRegistrationProperties properties = properties(server);
            FireflyJobRegistrar registrar = new FireflyJobRegistrar(
                    "billing-executor",
                    properties,
                    List.of(FireflyJobRegistration.builder(
                                    "billing-daily", "billingHandler", "0 0 2 * * *"
                            )
                            .name("Daily billing")
                            .zoneId("Asia/Shanghai")
                            .parameter("tenant", "primary")
                            .build()),
                    client,
                    com.firefly.executor.netty.AuthTokenProvider.fixed("ffk_test-key")
            );

            registrar.synchronizeJobs();

            assertEquals("ffk_test-key", token.get());
            assertTrue(body.get().contains("\"id\":\"billing-daily\""));
            assertTrue(body.get().contains("\"executorName\":\"billing-executor\""));
            assertTrue(body.get().contains("\"handlerName\":\"billingHandler\""));
            assertTrue(body.get().contains("\"param.tenant\":\"primary\""));
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void leavesExistingJobUnchangedUnlessUpdateIsEnabled() throws Exception {
        AtomicInteger updates = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"jobs\":[{\"id\":\"billing-daily\"}]}");
                return;
            }
            updates.incrementAndGet();
            respond(exchange, 200, "{\"status\":\"updated\"}");
        });
        NettyExecutorClient client = client();
        try {
            FireflyJobRegistration job = FireflyJobRegistration.of(
                    "billing-daily", "billingHandler", "0 0 2 * * *"
            );
            FireflyJobRegistrationProperties properties = properties(server);
            new FireflyJobRegistrar("billing-executor", properties, List.of(job), client).synchronizeJobs();
            assertEquals(0, updates.get());

            properties.setUpdateExisting(true);
            new FireflyJobRegistrar("billing-executor", properties, List.of(job), client).synchronizeJobs();
            assertEquals(1, updates.get());
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void rejectsAJobWhoseHandlerIsNotRegisteredLocally() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 500, "{}"));
        NettyExecutorClient client = client();
        try {
            FireflyJobRegistrationProperties properties = properties(server);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    new FireflyJobRegistrar(
                            "billing-executor",
                            properties,
                            List.of(FireflyJobRegistration.of("unknown-job", "missingHandler", "0 * * * * *")),
                            client
                    ).synchronizeJobs()
            );
            assertTrue(failure.getMessage().contains("handler is not registered locally"));
        } finally {
            client.close();
            server.stop(0);
        }
    }

    private NettyExecutorClient client() {
        return NettyExecutorClient.builder()
                .executorName("billing-executor")
                .serviceName("billing-service")
                .build()
                .registerHandler("billingHandler", ignored -> {
                });
    }

    private FireflyJobRegistrationProperties properties(HttpServer server) {
        FireflyJobRegistrationProperties properties = new FireflyJobRegistrationProperties();
        properties.setAdminUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setMaxAttempts(1);
        properties.setRequestTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/jobs", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
