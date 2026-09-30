package com.firefly.api.admin.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.JobDefinition;
import com.firefly.engine.ExecutionCommand;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.plugin.FireflyPluginContext;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpReplayTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void previewsDefinitionChangesAndRequiresConfirmationBeforeReplay() throws Exception {
        int port = freePort();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryJobRepository jobs = new InMemoryJobRepository(clock);
        JobDefinition original = definition("old");
        jobs.save(original, NOW.plusSeconds(60));
        jobs.enqueueManual(new ExecutionCommand(
                "exec-1", "root-1", 0, original, NOW, NOW, "node", 1L
        ));
        jobs.save(definition("new"), NOW.plusSeconds(60));
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        executions.saveExecution(new ExecutionRecord(
                "exec-1", "root-1", 0, "job", NOW, NOW,
                ExecutorDispatchMode.UNICAST, ExecutorCompletionPolicy.ALL_SUCCESS,
                ExecutionStatus.FAILED, 1, 0, "node", 1L, NOW, NOW
        ));
        executions.saveTargets(List.of(new ExecutionTargetRecord(
                "target-1", "exec-1", "instance", "gateway", null,
                ExecutionStatus.FAILED, 0, null, NOW, "failed", NOW, NOW
        )));
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder().clock(clock)
                .jobRepository(jobs).executionRepository(executions).build());
        try {
            String body = "{\"failedTargetsOnly\":true}";
            HttpResponse<String> preview = post(port, "/api/executions/exec-1/replay/preview", body);
            assertEquals(200, preview.statusCode());
            assertTrue(preview.body().contains("\"requiresConfirmation\":true"));
            assertTrue(preview.body().contains("parameter:mode"));

            assertEquals(409, post(port, "/api/executions/exec-1/replay", body).statusCode());
            HttpResponse<String> submitted = post(port, "/api/executions/exec-1/replay",
                    "{\"failedTargetsOnly\":true,\"confirmed\":true}");
            assertEquals(202, submitted.statusCode());
            JsonNode json = new ObjectMapper().readTree(submitted.body());
            String replayExecutionId = json.get("replayExecutionId").asText();
            assertTrue(replayExecutionId.startsWith("root-1@replay:"));
            assertTrue(jobs.findDispatch(replayExecutionId).isPresent());
            assertEquals("exec-1", jobs.findDispatch(replayExecutionId).orElseThrow()
                    .command().definition().parameters().get("firefly.replay.sourceExecutionId"));
        } finally {
            plugin.close();
        }
    }

    private static JobDefinition definition(String mode) {
        return JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).parameters(Map.of("mode", mode)).build();
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
