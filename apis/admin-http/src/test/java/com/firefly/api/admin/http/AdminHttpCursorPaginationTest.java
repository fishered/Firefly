package com.firefly.api.admin.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.audit.AuditRecord;
import com.firefly.audit.InMemoryAuditRepository;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.InMemoryJobHistoryRepository;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.store.JobHistoryRecord;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdminHttpCursorPaginationTest {
    @Test
    void pagesExecutionsWithStableTieBreakerAndFilters() throws Exception {
        int port = freePort();
        Instant now = Instant.parse("2026-10-15T08:00:00Z");
        InMemoryExecutionRepository repository = new InMemoryExecutionRepository();
        InMemoryAuditRepository audit = new InMemoryAuditRepository();
        InMemoryJobHistoryRepository history = new InMemoryJobHistoryRepository();
        for (int index = 0; index < 5; index++) {
            repository.saveExecution(new ExecutionRecord(
                    "execution-" + index, "root-" + index, 0,
                    index == 0 ? "other" : "job", now, now,
                    ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS,
                    index == 4 ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED,
                    1, 1, "node", 1L, now, now
            ));
            audit.append(new AuditRecord(
                    "audit-" + index, now, index == 0 ? "viewer" : "ops", "ADMIN",
                    "UPDATE", "jobs", "job", "SUCCESS", "", "", ""
            ));
            history.append(new JobHistoryRecord(
                    "history-" + index, index == 0 ? "other" : "job", index,
                    "UPDATE", "ops", "", "", now
            ));
        }
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder().executionRepository(repository)
                .jobRepository(new InMemoryJobRepository())
                .auditRepository(audit).jobHistoryRepository(history).build());
        try {
            List<String> ids = new ArrayList<>();
            String cursor = null;
            do {
                String path = "/api/executions?limit=2&jobId=job"
                        + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
                JsonNode page = new ObjectMapper().readTree(get(port, path).body());
                page.path("executions").forEach(item -> ids.add(item.path("executionId").asText()));
                cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            } while (cursor != null);

            assertEquals(List.of("execution-4", "execution-3", "execution-2", "execution-1"), ids);
            assertEquals(ids.size(), new HashSet<>(ids).size());
            JsonNode failed = new ObjectMapper().readTree(
                    get(port, "/api/executions?limit=10&status=FAILED").body());
            assertEquals(1, failed.path("executions").size());
            assertFalse(failed.path("hasMore").asBoolean());

            assertEquals(List.of("audit-4", "audit-3", "audit-2", "audit-1"),
                    collectIds(port, "/api/audit?limit=2&actor=ops", "items", "auditId"));
            assertEquals(List.of("history-4", "history-3", "history-2", "history-1"),
                    collectIds(port, "/api/jobs/job/history?limit=2&action=UPDATE",
                            "items", "historyId"));
        } finally {
            plugin.close();
        }
    }

    private List<String> collectIds(int port, String initialPath, String arrayName, String idName)
            throws Exception {
        List<String> ids = new ArrayList<>();
        String path = initialPath;
        while (path != null) {
            JsonNode page = new ObjectMapper().readTree(get(port, path).body());
            page.path(arrayName).forEach(item -> ids.add(item.path(idName).asText()));
            String cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            path = cursor == null ? null : initialPath + "&cursor="
                    + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        }
        assertEquals(ids.size(), new HashSet<>(ids).size());
        return ids;
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
