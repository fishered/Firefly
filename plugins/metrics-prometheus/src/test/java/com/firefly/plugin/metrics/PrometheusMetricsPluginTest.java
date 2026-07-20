package com.firefly.plugin.metrics;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.cluster.ShardHasher;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusMetricsPluginTest {
    @Test
    void exposesRuntimeLatencyAndHaMetrics() throws Exception {
        int port = freePort();
        SchedulerMetrics metrics = new SchedulerMetrics();
        metrics.observeScheduleDelay(Duration.ofMillis(75));
        metrics.observeOutboxAge(Duration.ofSeconds(2));
        metrics.observeAcknowledgementDelay(Duration.ofMillis(120));
        metrics.observeExecutionDuration(Duration.ofSeconds(3));
        metrics.recordLeaseRenewalFailure();
        metrics.recordDueBacklog();
        metrics.recordOutboxDeliveryExhaustion();
        metrics.executorConnections(3);
        metrics.recordExecutorRegistrationRejection();
        metrics.recordExecutorDisconnect();
        metrics.recordGatewayForward(Duration.ofMillis(20), true);
        metrics.recordGatewayForward(Duration.ofMillis(40), false);
        metrics.ownedShards(17);
        metrics.clockOffsetMillis(250);
        metrics.recordClockDriftWarning();
        metrics.recordClockSyncFailure();
        Instant now = Instant.parse("2026-07-16T10:00:00Z");
        InMemoryJobRepository jobs = new InMemoryJobRepository();
        jobs.save(JobDefinition.builder().id("due-job").name("Due").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build(), now.minusSeconds(12));
        jobs.save(JobDefinition.builder().id("future-job").name("Future").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build(), now.plusSeconds(60));
        PrometheusMetricsPlugin plugin = new PrometheusMetricsPlugin(
                new PrometheusMetricsOptions("127.0.0.1", port, "/metrics", Duration.ofSeconds(30))
        );
        plugin.start(FireflyPluginContext.builder()
                .clock(Clock.fixed(now, ZoneOffset.UTC))
                .jobRepository(jobs)
                .schedulerShardCount(4)
                .schedulerMetrics(metrics)
                .build());
        try {
            String body = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            ).body();
            assertTrue(body.contains("firefly_schedule_delay_seconds_bucket"));
            assertTrue(body.contains("firefly_shard_lease_renewal_failures_total 1"));
            assertTrue(body.contains("firefly_scheduler_due_backlog_events_total 1"));
            assertTrue(body.contains("firefly_dispatch_outbox_delivery_exhausted_total 1"));
            assertTrue(body.contains("firefly_executor_connections 3"));
            assertTrue(body.contains("firefly_executor_registration_rejections_total 1"));
            assertTrue(body.contains("firefly_executor_disconnects_total 1"));
            assertTrue(body.contains("firefly_gateway_forward_attempts_total 2"));
            assertTrue(body.contains("firefly_gateway_forward_successes_total 1"));
            assertTrue(body.contains("firefly_gateway_forward_failures_total 1"));
            assertTrue(body.contains("firefly_jobs_due_total 1"));
            assertTrue(body.contains("firefly_jobs_overdue_max_seconds 12.0"));
            int dueShard = ShardHasher.shardFor("due-job", 4);
            assertTrue(body.contains("firefly_scheduler_shard_due_jobs{shard=\"" + dueShard + "\"} 1"));
            assertTrue(body.contains("firefly_scheduler_owned_shards 17"));
            assertTrue(body.contains("firefly_database_clock_offset_milliseconds 250"));
            assertTrue(body.contains("firefly_database_clock_drift_warnings_total 1"));
            assertTrue(body.contains("firefly_database_clock_sync_failures_total 1"));
        } finally {
            plugin.close();
        }
    }

    private int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
