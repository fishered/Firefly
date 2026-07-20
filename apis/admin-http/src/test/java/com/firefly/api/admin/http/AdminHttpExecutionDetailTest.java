package com.firefly.api.admin.http;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.plugin.FireflyPluginContext;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpExecutionDetailTest {
    @Test
    void returnsExecutionTargetsAndCarryMetadata() throws Exception {
        int port = freePort();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        Instant now = Instant.parse("2026-07-16T10:00:00Z");
        String executionId = "job-1@retry:2";
        executions.saveExecution(new ExecutionRecord(
                executionId,
                "job-1@root",
                2,
                "job-1",
                now.minusSeconds(10),
                now,
                ExecutorDispatchMode.BROADCAST,
                ExecutorCompletionPolicy.QUORUM,
                ExecutionStatus.RUNNING,
                3,
                2,
                "scheduler-a",
                12L,
                now.plusSeconds(30),
                now,
                now
        ));
        executions.saveTargets(List.of(
                new ExecutionTargetRecord(
                        executionId + "@carry:instance:svc-a",
                        executionId,
                        "svc-a",
                        "gateway-a",
                        null,
                        ExecutionStatus.SUCCEEDED,
                        1,
                        now,
                        now,
                        "",
                        now,
                        now
                ),
                new ExecutionTargetRecord(
                        executionId + "@instance:svc-b",
                        executionId,
                        "svc-b",
                        "gateway-b",
                        null,
                        ExecutionStatus.RUNNING,
                        2,
                        now,
                        null,
                        "",
                        now,
                        now
                )
        ));
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder().executionRepository(executions).build());
        try {
            HttpResponse<String> response = get(port, "/api/executions/" + url(executionId));

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"completionPolicy\":\"QUORUM\""));
            assertTrue(response.body().contains("\"targetRecords\":2"));
            assertTrue(response.body().contains("\"carriedTargets\":1"));
            assertTrue(response.body().contains("\"carried\":true"));
            assertTrue(response.body().contains("\"gatewayNodeId\":\"gateway-b\""));
        } finally {
            plugin.close();
        }
    }

    @Test
    void returnsNotFoundForMissingExecution() throws Exception {
        int port = freePort();
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder()
                .executionRepository(new InMemoryExecutionRepository())
                .build());
        try {
            HttpResponse<String> response = get(port, "/api/executions/missing");

            assertEquals(404, response.statusCode());
            assertTrue(response.body().contains("execution_not_found"));
        } finally {
            plugin.close();
        }
    }

    @Test
    void cancelsRunningExecutionAndNotifiesConnectedTargets() throws Exception {
        int port = freePort();
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord(
                "cancel-exec", "job-1", now, now, ExecutorDispatchMode.UNICAST,
                ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.RUNNING,
                1, 1, "node-a", 7L, now, now
        ));
        AtomicInteger notified = new AtomicInteger();
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder()
                .executionRepository(executions)
                .executionCancellationDispatcher((executionId, reason) -> {
                    notified.incrementAndGet();
                    return 1;
                })
                .build());
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/executions/cancel-exec/cancel"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"maintenance\"}"))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(202, response.statusCode());
            assertEquals(ExecutionStatus.CANCELLED,
                    executions.findExecution("cancel-exec").orElseThrow().status());
            assertEquals(1, notified.get());
            assertTrue(response.body().contains("\"notifiedTargets\":1"));
        } finally {
            plugin.close();
        }
    }

    @Test
    void returnsAllAttemptsForARootExecution() throws Exception {
        int port = freePort();
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord(
                "root-1", "root-1", 0, "job-1", now, now,
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS,
                ExecutionStatus.FAILED, 1, 1, "node-a", 1L, now, now
        ));
        executions.saveExecution(new ExecutionRecord(
                "root-1@attempt:1", "root-1", 1, "job-1", now, now,
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS,
                ExecutionStatus.SUCCEEDED, 1, 1, "node-b", 2L, now, now
        ));
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder().executionRepository(executions).build());
        try {
            HttpResponse<String> response = get(port, "/api/executions/root/root-1");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"executionId\":\"root-1\""));
            assertTrue(response.body().contains("\"executionId\":\"root-1@attempt:1\""));
        } finally {
            plugin.close();
        }
    }

    @Test
    void batchCancelsExecutionsAndPersistsAnAuditRecord() throws Exception {
        int port = freePort();
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        for (String executionId : List.of("batch-1", "batch-2")) {
            executions.saveExecution(new ExecutionRecord(
                    executionId, executionId, now, now, ExecutorDispatchMode.UNICAST,
                    ExecutorCompletionPolicy.ALL_SUCCESS, ExecutionStatus.RUNNING,
                    1, 1, "node-a", 1L, now, now
            ));
        }
        com.firefly.audit.InMemoryAuditRepository audit = new com.firefly.audit.InMemoryAuditRepository();
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder()
                .executionRepository(executions)
                .auditRepository(audit)
                .build());
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/executions/batch-cancel"))
                    .header("Content-Type", "application/json")
                    .header("X-Firefly-Actor", "ops-user")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"executionIds\":[\"batch-1\",\"batch-2\"],\"reason\":\"deploy\"}"
                    ))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(202, response.statusCode());
            assertTrue(response.body().contains("\"cancelled\":2"));
            assertTrue(response.body().contains("\"executionId\":\"batch-1\",\"status\":\"CANCELLED\""));
            assertEquals(ExecutionStatus.CANCELLED, executions.findExecution("batch-1").orElseThrow().status());
            for (int attempt = 0; attempt < 20 && audit.listRecent(1).isEmpty(); attempt++) {
                Thread.sleep(10);
            }
            assertEquals("ops-user", audit.listRecent(1).getFirst().actor());
        } finally {
            plugin.close();
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
