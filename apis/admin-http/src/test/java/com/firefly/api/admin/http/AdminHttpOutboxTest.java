package com.firefly.api.admin.http;

import com.firefly.domain.JobDefinition;
import com.firefly.domain.CronSchedule;
import com.firefly.engine.ExecutionCommand;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.store.DispatchType;
import com.firefly.store.DueJobBatch;
import com.firefly.store.JobRepository;
import com.firefly.store.ScheduledJobRecord;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpOutboxTest {
    @Test
    void exposesDeadOutboxAndRestrictsManualRequeueToOperators() throws Exception {
        int port = freePort();
        StubJobRepository jobs = new StubJobRepository();
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "",
                Map.of("reader", AdminRole.READER, "operator", AdminRole.OPERATOR)
        ));
        plugin.start(FireflyPluginContext.builder().jobRepository(jobs).build());
        try {
            HttpResponse<String> list = request(port, "/api/outbox/dead", "GET", "reader");
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("\"outboxId\":\"dead-exec\""));
            assertTrue(list.body().contains("\"lastError\":\"gateway unavailable\""));

            assertEquals(403, request(port, "/api/outbox/dead-exec/requeue", "POST", "reader").statusCode());
            assertEquals(202, request(port, "/api/outbox/dead-exec/requeue", "POST", "operator").statusCode());
            assertTrue(jobs.requeued);
            assertEquals(404, request(port, "/api/outbox/dead-exec/requeue", "POST", "operator").statusCode());
        } finally {
            plugin.close();
        }
    }

    private HttpResponse<String> request(int port, String path, String method, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Firefly-Token", token);
        if ("GET".equals(method)) builder.GET();
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class StubJobRepository implements JobRepository {
        private final JobDefinition job = JobDefinition.builder()
                .id("dead-job").name("Dead job").handlerName("remote:orders:run")
                .schedule(new CronSchedule("0 * * * * *")).build();
        private boolean dead = true;
        private boolean requeued;

        @Override
        public List<DispatchOutboxRecord> listDeadDispatches(int limit) {
            if (!dead) return List.of();
            Instant now = Instant.parse("2026-07-16T10:00:00Z");
            return List.of(new DispatchOutboxRecord(
                    "dead-exec",
                    new ExecutionCommand("dead-exec", job, now, now, "node-a", 1L),
                    DispatchType.REMOTE,
                    DispatchOutboxStatus.DEAD,
                    5,
                    now,
                    "",
                    null,
                    null,
                    "gateway unavailable"
            ));
        }

        @Override
        public boolean requeueDeadDispatch(String outboxId, Instant now) {
            if (!dead || !"dead-exec".equals(outboxId)) return false;
            dead = false;
            requeued = true;
            return true;
        }

        @Override public void save(JobDefinition definition, Instant initialNextFireTime) { }
        @Override public Optional<ScheduledJobRecord> find(String jobId) { return Optional.empty(); }
        @Override public DueJobBatch findDueBatch(
                Instant now, int softLimit, int hardLimit, Set<String> excludedJobIds
        ) { return DueJobBatch.empty(); }
        @Override public boolean updateNextFireTime(
                String jobId, Instant expectedCurrentNextFireTime, Instant nextFireTime
        ) { return false; }
        @Override public List<ScheduledJobRecord> list() { return List.of(); }
        @Override public boolean setEnabled(String jobId, boolean enabled) { return false; }
        @Override public boolean delete(String jobId) { return false; }
    }
}
