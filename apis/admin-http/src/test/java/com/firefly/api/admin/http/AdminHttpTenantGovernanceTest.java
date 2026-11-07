package com.firefly.api.admin.http;

import com.firefly.audit.InMemoryAuditRepository;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.governance.InMemoryTenantQuotaStore;
import com.firefly.governance.TenantAdmissionRequest;
import com.firefly.governance.TenantDefinition;
import com.firefly.governance.TenantQuotaAwareJobRepository;
import com.firefly.metrics.SchedulerMetrics;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHttpTenantGovernanceTest {
    @Test
    void managesVersionedQuotasShowsUsageAndAuditsEveryChange() throws Exception {
        int port = freePort();
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        InMemoryTenantQuotaStore quotas = new InMemoryTenantQuotaStore();
        InMemoryAuditRepository audit = new InMemoryAuditRepository();
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "admin",
                Map.of("operator", AdminRole.OPERATOR, "admin", AdminRole.ADMIN)
        ));
        plugin.start(FireflyPluginContext.builder()
                .clock(Clock.fixed(now, ZoneOffset.UTC))
                .tenantQuotaStore(quotas)
                .auditRepository(audit)
                .build());
        try {
            assertEquals(403, request(port, "/api/tenants", "GET", "operator", "").statusCode());
            HttpResponse<String> created = request(port, "/api/tenants/blue", "PUT", "admin", """
                    {"expectedVersion":0,"enabled":true,"weight":3,"maxConcurrent":2,
                     "maxCpuMillis":1000,"maxMemoryBytes":2048,"maxBacklog":20,
                     "maxTriggerRatePerSecond":5}
                    """);
            assertEquals(201, created.statusCode());
            assertTrue(created.body().contains("\"version\":1"));
            assertTrue(created.body().contains("\"weight\":3"));

            assertTrue(quotas.tryReserve(new TenantAdmissionRequest(
                    "running", "blue", 1, 250, 512, 3, 1
            ), now, Duration.ofMinutes(1)).admitted());
            HttpResponse<String> detail = request(port, "/api/tenants/blue", "GET", "admin", "");
            assertEquals(200, detail.statusCode());
            assertTrue(detail.body().contains("\"activeExecutions\":1"));
            assertTrue(detail.body().contains("\"reservedCpuMillis\":250"));
            assertTrue(detail.body().contains("\"backlog\":3"));

            assertEquals(409, request(port, "/api/tenants/blue", "PUT", "admin",
                    "{\"expectedVersion\":0,\"weight\":7}").statusCode());
            HttpResponse<String> updated = request(port, "/api/tenants/blue", "PUT", "admin",
                    "{\"expectedVersion\":1,\"weight\":7,\"maxConcurrent\":4}");
            assertEquals(200, updated.statusCode());
            assertTrue(updated.body().contains("\"version\":2"));
            assertEquals(7, quotas.find("blue").orElseThrow().weight());

            long auditDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (audit.listRecent(10).size() < 3 && System.nanoTime() < auditDeadline) Thread.sleep(10);
            assertEquals(3, audit.listRecent(10).size());
            assertTrue(audit.listRecent(10).stream().allMatch(record -> record.resourceType().equals("tenants")));
            assertTrue(audit.listRecent(10).stream().anyMatch(record -> !record.afterPayload().isBlank()));
            assertTrue(audit.listRecent(10).stream().anyMatch(record -> record.outcome().equals("FAILURE")));
            assertFalse(audit.listRecent(10).getFirst().actor().isBlank());
        } finally {
            plugin.close();
        }
    }

    @Test
    void jobApiSurfacesTenantResourcesAndExactBacklogRejection() throws Exception {
        int port = freePort();
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryTenantQuotaStore quotas = new InMemoryTenantQuotaStore();
        quotas.save(new TenantDefinition(
                "default", true, 1, 0, 0, 0, 1, 0, 1, now
        ), 1, now);
        InMemoryJobRepository rawJobs = new InMemoryJobRepository();
        var jobs = TenantQuotaAwareJobRepository.wrap(rawJobs, quotas, clock, new SchedulerMetrics());
        jobs.save(JobDefinition.builder()
                .id("governed-job")
                .name("Governed job")
                .handlerName("run")
                .schedule(new CronSchedule("0 * * * * *"))
                .parameters(Map.of(
                        "firefly.resource.tenantId", "default",
                        "firefly.resource.priority", "7",
                        "firefly.resource.cpuMillis", "250",
                        "firefly.resource.memoryBytes", "1024"
                ))
                .build(), now.plusSeconds(60));
        AdminHttpPlugin plugin = new AdminHttpPlugin(new AdminHttpOptions(
                "127.0.0.1", port, Duration.ofSeconds(30), "admin",
                Map.of("admin", AdminRole.ADMIN)
        ));
        plugin.start(FireflyPluginContext.builder()
                .clock(clock)
                .jobRepository(jobs)
                .tenantQuotaStore(quotas)
                .build());
        try {
            HttpResponse<String> detail = request(port, "/api/jobs/governed-job", "GET", "admin", "");
            assertEquals(200, detail.statusCode());
            assertTrue(detail.body().contains("\"tenantId\":\"default\""));
            assertTrue(detail.body().contains("\"queuePriority\":7"));
            assertTrue(detail.body().contains("\"resourceCpuMillis\":250"));
            assertTrue(detail.body().contains("\"resourceMemoryBytes\":1024"));

            assertEquals(202, request(
                    port, "/api/jobs/governed-job/trigger", "POST", "admin", "{}"
            ).statusCode());
            HttpResponse<String> rejected = request(
                    port, "/api/jobs/governed-job/trigger", "POST", "admin", "{}"
            );
            assertEquals(429, rejected.statusCode());
            assertTrue(rejected.body().contains("\"reason\":\"BACKLOG_QUOTA_EXCEEDED\""));
            assertTrue(rejected.body().contains("\"tenantId\":\"default\""));
        } finally {
            plugin.close();
        }
    }

    private HttpResponse<String> request(int port, String path, String method, String token, String body)
            throws Exception {
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
