package com.firefly.api.admin.http;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.audit.InMemoryAuditRepository;
import com.firefly.plugin.FireflyPluginConfiguration;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.trigger.InMemoryTriggerInbox;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpTriggerSecurityTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final String SECRET = "test-event-ingress-secret";
    private static final String BODY = """
            {"eventId":"event-1","eventType":"order.created","idempotencyKey":"key-1","payload":"{}"}
            """;

    @Test
    void requiresHmacRejectsNonceReplayAndKeepsEventIdempotent() throws Exception {
        int port = freePort();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryJobRepository jobs = new InMemoryJobRepository(clock);
        jobs.save(job(), NOW.plusSeconds(60));
        InMemoryTriggerInbox inbox = new InMemoryTriggerInbox();
        AdminHttpPlugin plugin = plugin(port, clock, jobs, inbox, new InMemoryAuditRepository(), 4096);
        try {
            assertEquals(401, post(port, "/api/triggers/job", BODY, null, null).statusCode());
            assertEquals(401, post(port, "/api/triggers/job", BODY, "nonce-invalid", "invalid").statusCode());
            Instant expired = NOW.minus(Duration.ofMinutes(6));
            assertEquals(401, postAt(port, "/api/triggers/job", BODY, "nonce-expired", expired,
                    signature("job", "nonce-expired", expired)).statusCode());

            HttpResponse<String> accepted = post(port, "/api/triggers/job", BODY, "nonce-1",
                    signature("job", "nonce-1"));
            assertEquals(202, accepted.statusCode());
            assertTrue(accepted.body().contains("\"status\":\"queued\""));

            assertEquals(409, post(port, "/api/triggers/job", BODY, "nonce-1",
                    signature("job", "nonce-1")).statusCode());
            HttpResponse<String> duplicate = post(port, "/api/triggers/job", BODY, "nonce-2",
                    signature("job", "nonce-2"));
            assertEquals(200, duplicate.statusCode());
            assertTrue(duplicate.body().contains("\"duplicate\":true"));
            assertEquals(1, jobs.outboxCounts().values().stream().mapToLong(Long::longValue).sum());
        } finally {
            plugin.close();
        }
    }

    @Test
    void concurrentSignedRetriesCreateExactlyOneRootExecution() throws Exception {
        int port = freePort();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryJobRepository jobs = new InMemoryJobRepository(clock);
        jobs.save(job(), NOW.plusSeconds(60));
        AdminHttpPlugin plugin = plugin(
                port, clock, jobs, new InMemoryTriggerInbox(), new InMemoryAuditRepository(), 4096
        );
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(16)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 16; index++) {
                String nonce = "concurrent-nonce-" + index;
                String signed = signature("job", nonce);
                results.add(pool.submit(() -> {
                    start.await();
                    return post(port, "/api/triggers/job", BODY, nonce, signed).statusCode();
                }));
            }
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var result : results) statuses.add(result.get());
            assertEquals(1, statuses.stream().filter(status -> status == 202).count());
            assertEquals(15, statuses.stream().filter(status -> status == 200).count());
            assertEquals(1, jobs.outboxCounts().values().stream().mapToLong(Long::longValue).sum());
        } finally {
            plugin.close();
        }
    }

    @Test
    void exposesFailureReasonAndReplaysOnceAfterOperatorFix() throws Exception {
        int port = freePort();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryJobRepository jobs = new InMemoryJobRepository(clock);
        InMemoryTriggerInbox inbox = new InMemoryTriggerInbox();
        InMemoryAuditRepository audit = new InMemoryAuditRepository();
        AdminHttpPlugin plugin = plugin(port, clock, jobs, inbox, audit, 4096);
        try {
            assertEquals(400, post(port, "/api/triggers/job", BODY, "nonce-failure",
                    signature("job", "nonce-failure")).statusCode());
            String failed = get(port, "/api/triggers/failed").body();
            assertTrue(failed.contains("\"jobId\":\"job\""));
            assertTrue(failed.contains("job not found"));

            jobs.save(job(), NOW.plusSeconds(60));
            assertEquals(202, post(port, "/api/triggers/failed/key-1/replay", "{}", null, null).statusCode());
            assertEquals(409, post(port, "/api/triggers/failed/key-1/replay", "{}", null, null).statusCode());
            assertEquals(1, jobs.outboxCounts().values().stream().mapToLong(Long::longValue).sum());
            awaitAuditCount(audit, "POST /api/triggers/failed/key-1/replay", 2);
            var replayAudits = audit.listRecent(10).stream()
                    .filter(record -> record.action().equals("POST /api/triggers/failed/key-1/replay"))
                    .toList();
            assertEquals(2, replayAudits.size());
            assertEquals(1, replayAudits.stream().filter(record -> record.outcome().equals("SUCCESS")).count());
            assertEquals(1, replayAudits.stream().filter(record -> record.outcome().equals("FAILURE")).count());
        } finally {
            plugin.close();
        }
    }

    @Test
    void acknowledgesFailedEventOnceAndAuditsBothAttempts() throws Exception {
        int port = freePort();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InMemoryAuditRepository audit = new InMemoryAuditRepository();
        InMemoryTriggerInbox inbox = new InMemoryTriggerInbox();
        AdminHttpPlugin plugin = plugin(
                port, clock, new InMemoryJobRepository(clock), inbox, audit, 4096
        );
        try {
            assertEquals(400, post(port, "/api/triggers/job", BODY, "nonce-ack",
                    signature("job", "nonce-ack")).statusCode());
            String path = "/api/triggers/failed/key-1/acknowledge";
            assertEquals(202, post(port, path, "{}", null, null).statusCode());
            assertEquals(409, post(port, path, "{}", null, null).statusCode());
            assertTrue(!get(port, "/api/triggers/failed").body().contains("key-1"));
            awaitAuditCount(audit, "POST " + path, 2);
            var records = audit.listRecent(10).stream()
                    .filter(record -> record.action().equals("POST " + path)).toList();
            assertEquals(2, records.size());
            assertEquals(1, records.stream().filter(record -> record.outcome().equals("SUCCESS")).count());
            assertEquals(1, records.stream().filter(record -> record.outcome().equals("FAILURE")).count());

            var slashKey = new com.firefly.trigger.EventTrigger(
                    "slash-event", "order.created", "tenant/order-1", "{}", NOW,
                    com.firefly.trigger.EventTrigger.TriggerStatus.RECEIVED, null
            );
            assertTrue(inbox.receive("job", slashKey));
            assertTrue(inbox.markFailed("tenant/order-1", NOW, "operator test"));
            assertEquals(202, post(port,
                    "/api/triggers/failed/tenant%2Forder-1/acknowledge", "{}", null, null).statusCode());
        } finally {
            plugin.close();
        }
    }

    @Test
    void appliesDedicatedTriggerBodyLimit() throws Exception {
        int port = freePort();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AdminHttpPlugin plugin = plugin(port, clock, new InMemoryJobRepository(clock),
                new InMemoryTriggerInbox(), new InMemoryAuditRepository(), 32);
        try {
            assertEquals(413, post(port, "/api/triggers/job", BODY, "nonce", "invalid").statusCode());
        } finally {
            plugin.close();
        }
    }

    private AdminHttpPlugin plugin(int port, Clock clock, InMemoryJobRepository jobs,
                                   InMemoryTriggerInbox inbox, InMemoryAuditRepository audit,
                                   int maxBodyBytes) {
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30)
        ));
        plugin.start(FireflyPluginContext.builder().clock(clock).jobRepository(jobs).triggerInbox(inbox)
                .auditRepository(audit)
                .configuration(new FireflyPluginConfiguration(Map.of(
                        "firefly.trigger.hmac-secret", SECRET,
                        "firefly.trigger.max-request-body-bytes", Integer.toString(maxBodyBytes)
                ), Map.of())).build());
        return plugin;
    }

    private JobDefinition job() {
        return JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build();
    }

    private HttpResponse<String> post(int port, String path, String body,
                                      String nonce, String signature) throws Exception {
        return postAt(port, path, body, nonce, NOW, signature);
    }

    private HttpResponse<String> postAt(int port, String path, String body,
                                        String nonce, Instant timestamp, String signature) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (nonce != null) builder.header("X-Firefly-Timestamp", timestamp.toString())
                .header("X-Firefly-Nonce", nonce).header("X-Firefly-Signature", signature);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String signature(String jobId, String nonce) throws Exception {
        return signature(jobId, nonce, NOW);
    }

    private String signature(String jobId, String nonce, Instant timestamp) throws Exception {
        String canonical = timestamp + "\n" + nonce + "\n" + jobId
                + "\nevent-1\norder.created\nkey-1\n{}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private void awaitAuditCount(InMemoryAuditRepository audit, String action, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            long count = audit.listRecent(100).stream().filter(record -> record.action().equals(action)).count();
            if (count >= expected) return;
            Thread.sleep(10);
        }
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
