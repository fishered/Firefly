package com.firefly.plugin.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.store.ScheduledJobRecord;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.execution.ExecutionStatus;
import com.firefly.metrics.SchedulerMetrics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Exposes Firefly state in Prometheus text format as an optional plugin.
 */
public final class PrometheusMetricsPlugin implements FireflyPlugin {
    private final PrometheusMetricsOptions options;
    private HttpServer server;
    private FireflyPluginContext context;

    public PrometheusMetricsPlugin() {
        this(PrometheusMetricsOptions.defaults());
    }

    public PrometheusMetricsPlugin(PrometheusMetricsOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public String id() {
        return "metrics-prometheus";
    }

    @Override
    public String displayName() {
        return "Prometheus Metrics";
    }

    @Override
    public String description() {
        return "Prometheus-compatible scheduler and cluster metrics endpoint";
    }

    @Override
    public void start(FireflyPluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        try {
            server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 0);
            server.createContext(options.path(), this::handleMetrics);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start Firefly Prometheus metrics plugin", e);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        String body = render();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String render() {
        List<ScheduledJobRecord> jobs = context.jobRepository()
                .map(repository -> repository.list())
                .orElse(List.of());
        long enabledJobs = jobs.stream().filter(record -> record.definition().enabled()).count();
        int onlineNodes = context.nodeRegistry()
                .map(registry -> registry.listOnline(context.clock().instant(), options.heartbeatTimeout()).size())
                .orElse(0);
        long nextFireEpochSeconds = jobs.stream()
                .map(ScheduledJobRecord::nextFireTime)
                .min(Instant::compareTo)
                .map(Instant::getEpochSecond)
                .orElse(0L);
        Instant now = context.clock().instant();
        long dueJobs = jobs.stream()
                .filter(record -> record.definition().enabled())
                .filter(record -> !record.nextFireTime().isAfter(now))
                .count();
        double maxJobOverdueSeconds = jobs.stream()
                .filter(record -> record.definition().enabled())
                .filter(record -> !record.nextFireTime().isAfter(now))
                .mapToLong(record -> Math.max(0, Duration.between(record.nextFireTime(), now).toMillis()))
                .max()
                .orElse(0L) / 1000.0;
        long outboxPending = context.jobRepository().map(repository -> repository.outboxCounts().entrySet().stream()
                .filter(entry -> entry.getKey() != DispatchOutboxStatus.DONE
                        && entry.getKey() != DispatchOutboxStatus.DEAD)
                .mapToLong(java.util.Map.Entry::getValue).sum()).orElse(0L);
        long outboxDead = context.jobRepository().map(repository ->
                repository.outboxCounts().getOrDefault(DispatchOutboxStatus.DEAD, 0L)).orElse(0L);
        long executionsRunning = context.executionRepository().map(repository ->
                repository.statusCounts().getOrDefault(ExecutionStatus.RUNNING, 0L)).orElse(0L);
        long executionsFailed = context.executionRepository().map(repository -> repository.statusCounts().entrySet().stream()
                .filter(entry -> entry.getKey() == ExecutionStatus.FAILED || entry.getKey() == ExecutionStatus.TIMEOUT)
                .mapToLong(java.util.Map.Entry::getValue).sum()).orElse(0L);
        double oldestOutboxAgeSeconds = context.jobRepository()
                .flatMap(repository -> repository.oldestActiveDispatchTime())
                .map(oldest -> Math.max(0, Duration.between(oldest, context.clock().instant()).toMillis()) / 1000.0)
                .orElse(0.0);
        java.util.Map<Integer, Long> dueByShard = context.jobRepository()
                .map(repository -> repository.dueCountsByShard(now, context.schedulerShardCount()))
                .orElse(java.util.Map.of());

        /**
         * Metrics are derived from plugin context snapshots. The scheduler core does
         * not know Prometheus exists, which keeps monitoring replaceable.
         */
        StringBuilder body = new StringBuilder("""
                # HELP firefly_plugin_up Whether the Firefly Prometheus metrics plugin is running.
                # TYPE firefly_plugin_up gauge
                firefly_plugin_up{plugin="metrics-prometheus"} 1
                # HELP firefly_jobs_total Number of known Firefly jobs.
                # TYPE firefly_jobs_total gauge
                firefly_jobs_total %d
                # HELP firefly_jobs_enabled Number of enabled Firefly jobs.
                # TYPE firefly_jobs_enabled gauge
                firefly_jobs_enabled %d
                # HELP firefly_nodes_online Number of online Firefly nodes visible to the plugin.
                # TYPE firefly_nodes_online gauge
                firefly_nodes_online %d
                # HELP firefly_next_fire_time_epoch_seconds Earliest next fire time across known jobs.
                # TYPE firefly_next_fire_time_epoch_seconds gauge
                firefly_next_fire_time_epoch_seconds %d
                # HELP firefly_jobs_due_total Enabled jobs whose next fire time is due.
                # TYPE firefly_jobs_due_total gauge
                firefly_jobs_due_total %d
                # HELP firefly_jobs_overdue_max_seconds Maximum overdue age among enabled due jobs.
                # TYPE firefly_jobs_overdue_max_seconds gauge
                firefly_jobs_overdue_max_seconds %s
                # TYPE firefly_dispatch_outbox_pending gauge
                firefly_dispatch_outbox_pending %d
                # TYPE firefly_dispatch_outbox_dead gauge
                firefly_dispatch_outbox_dead %d
                # TYPE firefly_executions_running gauge
                firefly_executions_running %d
                # TYPE firefly_executions_failed gauge
                firefly_executions_failed %d
                # HELP firefly_dispatch_outbox_oldest_age_seconds Age of the oldest active outbox record.
                # TYPE firefly_dispatch_outbox_oldest_age_seconds gauge
                firefly_dispatch_outbox_oldest_age_seconds %s
                """.formatted(jobs.size(), enabledJobs, onlineNodes, nextFireEpochSeconds,
                        dueJobs, Double.toString(maxJobOverdueSeconds),
                        outboxPending, outboxDead, executionsRunning, executionsFailed,
                        Double.toString(oldestOutboxAgeSeconds)));
        context.schedulerMetrics().ifPresent(metrics -> appendRuntimeMetrics(body, metrics.snapshot()));
        body.append("# HELP firefly_scheduler_shard_due_jobs Due jobs grouped by scheduler shard.\n")
                .append("# TYPE firefly_scheduler_shard_due_jobs gauge\n");
        java.util.stream.IntStream.range(0, context.schedulerShardCount()).forEach(shard ->
                body.append("firefly_scheduler_shard_due_jobs{shard=\"")
                        .append(shard).append("\"} ").append(dueByShard.getOrDefault(shard, 0L)).append('\n')
        );
        return body.toString();
    }

    private void appendRuntimeMetrics(StringBuilder body, SchedulerMetrics.Snapshot snapshot) {
        appendHistogram(body, "firefly_schedule_delay_seconds",
                "Delay from scheduled fire time to durable dispatch creation.", snapshot.scheduleDelay());
        appendHistogram(body, "firefly_outbox_claim_age_seconds",
                "Age of outbox records when claimed by a worker.", snapshot.outboxAge());
        appendHistogram(body, "firefly_executor_ack_delay_seconds",
                "Delay from dispatch creation to executor acknowledgement.", snapshot.acknowledgementDelay());
        appendHistogram(body, "firefly_execution_duration_seconds",
                "Observed local and remote execution duration.", snapshot.executionDuration());
        appendHistogram(body, "firefly_gateway_forward_duration_seconds",
                "Latency of Gateway-to-Gateway forwarding attempts.", snapshot.gatewayForwardDuration());
        body.append("# HELP firefly_shard_lease_renewal_failures_total Failed shard lease renewals.\n")
                .append("# TYPE firefly_shard_lease_renewal_failures_total counter\n")
                .append("firefly_shard_lease_renewal_failures_total ").append(snapshot.leaseRenewalFailures()).append('\n')
                .append("# HELP firefly_scheduler_due_backlog_events_total Scheduler ticks that reached the due limit.\n")
                .append("# TYPE firefly_scheduler_due_backlog_events_total counter\n")
                .append("firefly_scheduler_due_backlog_events_total ").append(snapshot.dueBacklogEvents()).append('\n')
                .append("# HELP firefly_dispatch_outbox_delivery_exhausted_total Dispatches that exhausted delivery attempts.\n")
                .append("# TYPE firefly_dispatch_outbox_delivery_exhausted_total counter\n")
                .append("firefly_dispatch_outbox_delivery_exhausted_total ")
                .append(snapshot.outboxDeliveryExhaustions()).append('\n')
                .append("# HELP firefly_executor_connections Active executor connections on this Gateway.\n")
                .append("# TYPE firefly_executor_connections gauge\n")
                .append("firefly_executor_connections ").append(snapshot.executorConnections()).append('\n')
                .append("# HELP firefly_executor_registration_rejections_total Rejected executor registrations.\n")
                .append("# TYPE firefly_executor_registration_rejections_total counter\n")
                .append("firefly_executor_registration_rejections_total ")
                .append(snapshot.executorRegistrationRejections()).append('\n')
                .append("# HELP firefly_executor_disconnects_total Executor connection disconnects.\n")
                .append("# TYPE firefly_executor_disconnects_total counter\n")
                .append("firefly_executor_disconnects_total ").append(snapshot.executorDisconnects()).append('\n')
                .append("# TYPE firefly_gateway_forward_attempts_total counter\n")
                .append("firefly_gateway_forward_attempts_total ").append(snapshot.gatewayForwardAttempts()).append('\n')
                .append("# TYPE firefly_gateway_forward_successes_total counter\n")
                .append("firefly_gateway_forward_successes_total ").append(snapshot.gatewayForwardSuccesses()).append('\n')
                .append("# TYPE firefly_gateway_forward_failures_total counter\n")
                .append("firefly_gateway_forward_failures_total ").append(snapshot.gatewayForwardFailures()).append('\n')
                .append("# HELP firefly_scheduler_owned_shards Shards currently owned by this node.\n")
                .append("# TYPE firefly_scheduler_owned_shards gauge\n")
                .append("firefly_scheduler_owned_shards ").append(snapshot.ownedShards()).append('\n')
                .append("# HELP firefly_database_clock_offset_milliseconds Calibrated database minus JVM clock offset.\n")
                .append("# TYPE firefly_database_clock_offset_milliseconds gauge\n")
                .append("firefly_database_clock_offset_milliseconds ").append(snapshot.clockOffsetMillis()).append('\n')
                .append("# TYPE firefly_database_clock_drift_warnings_total counter\n")
                .append("firefly_database_clock_drift_warnings_total ").append(snapshot.clockDriftWarnings()).append('\n')
                .append("# TYPE firefly_database_clock_sync_failures_total counter\n")
                .append("firefly_database_clock_sync_failures_total ").append(snapshot.clockSyncFailures()).append('\n');
    }

    private void appendHistogram(
            StringBuilder body,
            String name,
            String help,
            SchedulerMetrics.DurationHistogramSnapshot snapshot
    ) {
        body.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" histogram\n");
        snapshot.buckets().forEach(bucket -> body.append(name).append("_bucket{le=\"")
                .append(bucket.upperBound()).append("\"} ").append(bucket.cumulativeCount()).append('\n'));
        body.append(name).append("_count ").append(snapshot.count()).append('\n')
                .append(name).append("_sum ").append(snapshot.sumSeconds()).append('\n')
                .append("# TYPE ").append(name).append("_max gauge\n")
                .append(name).append("_max ").append(snapshot.maxSeconds()).append('\n');
    }
}
