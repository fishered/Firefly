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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Exposes Firefly state in Prometheus text format as an optional plugin.
 */
public final class PrometheusMetricsPlugin implements FireflyPlugin {
    private final PrometheusMetricsOptions options;
    private HttpServer server;
    private FireflyPluginContext context;
    private ExecutorService executor;

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
            executor = new java.util.concurrent.ThreadPoolExecutor(
                    8, 8, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(64),
                    runnable -> new Thread(runnable, "firefly-prometheus-http"),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
            );
            server.setExecutor(executor);
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
        if (executor != null) executor.shutdownNow();
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
        Map<DispatchOutboxStatus, Long> outboxCounts = context.jobRepository()
                .map(repository -> repository.outboxCounts()).orElse(Map.of());
        long outboxPending = outboxCounts.entrySet().stream()
                .filter(entry -> entry.getKey() != DispatchOutboxStatus.DONE
                        && entry.getKey() != DispatchOutboxStatus.DEAD)
                .mapToLong(Map.Entry::getValue).sum();
        long outboxDead = outboxCounts.getOrDefault(DispatchOutboxStatus.DEAD, 0L);
        Map<ExecutionStatus, Long> executionCounts = context.executionRepository()
                .map(repository -> repository.statusCounts()).orElse(Map.of());
        long executionsRunning = executionCounts.getOrDefault(ExecutionStatus.RUNNING, 0L);
        long executionsFailed = executionCounts.entrySet().stream()
                .filter(entry -> entry.getKey() == ExecutionStatus.FAILED || entry.getKey() == ExecutionStatus.TIMEOUT)
                .mapToLong(Map.Entry::getValue).sum();
        double oldestOutboxAgeSeconds = context.jobRepository()
                .flatMap(repository -> repository.oldestActiveDispatchTime())
                .map(oldest -> Math.max(0, Duration.between(oldest, context.clock().instant()).toMillis()) / 1000.0)
                .orElse(0.0);
        java.util.Map<Integer, Long> dueByShard = context.jobRepository()
                .map(repository -> repository.dueCountsByShard(now, context.schedulerShardCount()))
                .orElse(java.util.Map.of());
        long failedTriggers = context.triggerInbox().map(com.firefly.trigger.TriggerInbox::countFailed).orElse(0L);

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
        body.append("# HELP firefly_trigger_failed_events Unresolved failed external trigger events.\n")
                .append("# TYPE firefly_trigger_failed_events gauge\n")
                .append("firefly_trigger_failed_events ").append(failedTriggers).append('\n');
        context.schedulerMetrics().ifPresent(metrics -> {
            appendRuntimeMetrics(body, metrics.snapshot());
            appendTenantAdmissionMetrics(body, metrics.tenantAdmissionRejections());
            appendTenantCapacityHealthMetrics(body, metrics);
        });
        context.tenantQuotaStore().ifPresent(store -> appendTenantUsageMetrics(body, store, now));
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
                .append("# HELP firefly_executor_overload_acks_total Executor overload acknowledgements.\n")
                .append("# TYPE firefly_executor_overload_acks_total counter\n")
                .append("firefly_executor_overload_acks_total ").append(snapshot.executorOverloadAcks()).append('\n')
                .append("# HELP firefly_executor_client_active_executions Active local executor handler calls.\n")
                .append("# TYPE firefly_executor_client_active_executions gauge\n")
                .append("firefly_executor_client_active_executions ")
                .append(snapshot.executorClientActiveExecutions()).append('\n')
                .append("# HELP firefly_executor_client_queued_executions Accepted local executor work waiting to run.\n")
                .append("# TYPE firefly_executor_client_queued_executions gauge\n")
                .append("firefly_executor_client_queued_executions ")
                .append(snapshot.executorClientQueuedExecutions()).append('\n')
                .append("# HELP firefly_executor_client_max_concurrent_executions Configured local execution concurrency limit.\n")
                .append("# TYPE firefly_executor_client_max_concurrent_executions gauge\n")
                .append("firefly_executor_client_max_concurrent_executions ")
                .append(snapshot.executorClientMaxConcurrentExecutions()).append('\n')
                .append("# HELP firefly_executor_client_queue_capacity Configured local executor queue capacity.\n")
                .append("# TYPE firefly_executor_client_queue_capacity gauge\n")
                .append("firefly_executor_client_queue_capacity ")
                .append(snapshot.executorClientQueueCapacity()).append('\n')
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
                .append("firefly_database_clock_sync_failures_total ").append(snapshot.clockSyncFailures()).append('\n')
                .append("# HELP firefly_local_worker_active Active local scheduler handler executions.\n")
                .append("# TYPE firefly_local_worker_active gauge\n")
                .append("firefly_local_worker_active ").append(snapshot.localWorkerActive()).append('\n')
                .append("# HELP firefly_local_worker_max_concurrency Configured local scheduler concurrency limit.\n")
                .append("# TYPE firefly_local_worker_max_concurrency gauge\n")
                .append("firefly_local_worker_max_concurrency ")
                .append(snapshot.localWorkerMaxConcurrency()).append('\n')
                .append("# HELP firefly_local_worker_rejections_total Rejected local scheduler submissions.\n")
                .append("# TYPE firefly_local_worker_rejections_total counter\n")
                .append("firefly_local_worker_rejections_total ").append(snapshot.localWorkerRejections()).append('\n')
                .append("# HELP firefly_batch_progress_updates_total Persisted batch progress updates.\n")
                .append("# TYPE firefly_batch_progress_updates_total counter\n")
                .append("firefly_batch_progress_updates_total ").append(snapshot.batchProgressUpdates()).append('\n')
                .append("# HELP firefly_batch_progress_dropped_total Intermediate batch progress updates dropped by rate limiting.\n")
                .append("# TYPE firefly_batch_progress_dropped_total counter\n")
                .append("firefly_batch_progress_dropped_total ").append(snapshot.batchProgressDropped()).append('\n')
                .append("# HELP firefly_batch_shard_failures_total Failed or timed-out batch shard results.\n")
                .append("# TYPE firefly_batch_shard_failures_total counter\n")
                .append("firefly_batch_shard_failures_total ").append(snapshot.batchShardFailures()).append('\n');
        body.append("# HELP firefly_executor_resource_instances Executor instances reporting live capacity.\n")
                .append("# TYPE firefly_executor_resource_instances gauge\n")
                .append("firefly_executor_resource_instances ").append(snapshot.executorResourceInstances()).append('\n')
                .append("# TYPE firefly_executor_available_cpu_millis gauge\n")
                .append("firefly_executor_available_cpu_millis ").append(snapshot.executorAvailableCpuMillis()).append('\n')
                .append("# TYPE firefly_executor_available_memory_bytes gauge\n")
                .append("firefly_executor_available_memory_bytes ").append(snapshot.executorAvailableMemoryBytes()).append('\n')
                .append("# TYPE firefly_executor_resource_active_executions gauge\n")
                .append("firefly_executor_resource_active_executions ").append(snapshot.executorResourceActiveExecutions()).append('\n')
                .append("# HELP firefly_executor_resource_reservation_rejections_total Atomic capacity reservations rejected by a newer snapshot.\n")
                .append("# TYPE firefly_executor_resource_reservation_rejections_total counter\n")
                .append("firefly_executor_resource_reservation_rejections_total ")
                .append(snapshot.executorResourceReservationRejections()).append('\n');
        body.append("# HELP firefly_backfill_active_operations Pending or running durable backfills.\n")
                .append("# TYPE firefly_backfill_active_operations gauge\n")
                .append("firefly_backfill_active_operations ").append(snapshot.backfillActiveOperations()).append('\n')
                .append("# TYPE firefly_backfill_paused_operations gauge\n")
                .append("firefly_backfill_paused_operations ").append(snapshot.backfillPausedOperations()).append('\n')
                .append("# TYPE firefly_backfill_failed_items gauge\n")
                .append("firefly_backfill_failed_items ").append(snapshot.backfillFailedItems()).append('\n')
                .append("# TYPE firefly_backfill_oldest_active_age_seconds gauge\n")
                .append("firefly_backfill_oldest_active_age_seconds ").append(snapshot.backfillOldestActiveAgeSeconds()).append('\n')
                .append("# TYPE firefly_backfill_operations_processed_total counter\n")
                .append("firefly_backfill_operations_processed_total ").append(snapshot.backfillOperationsProcessed()).append('\n')
                .append("# TYPE firefly_backfill_worker_failures_total counter\n")
                .append("firefly_backfill_worker_failures_total ").append(snapshot.backfillWorkerFailures()).append('\n');
        body.append("# TYPE firefly_trigger_accepted_total counter\n")
                .append("firefly_trigger_accepted_total ").append(snapshot.triggerAccepted()).append('\n')
                .append("# TYPE firefly_trigger_duplicates_total counter\n")
                .append("firefly_trigger_duplicates_total ").append(snapshot.triggerDuplicates()).append('\n')
                .append("# TYPE firefly_trigger_failures_total counter\n")
                .append("firefly_trigger_failures_total ").append(snapshot.triggerFailures()).append('\n')
                .append("# TYPE firefly_trigger_replays_total counter\n")
                .append("firefly_trigger_replays_total ").append(snapshot.triggerReplays()).append('\n')
                .append("# TYPE firefly_trigger_security_rejections_total counter\n")
                .append("firefly_trigger_security_rejections_total ")
                .append(snapshot.triggerSecurityRejections()).append('\n');
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

    private void appendTenantAdmissionMetrics(
            StringBuilder body,
            java.util.Map<SchedulerMetrics.TenantAdmissionMetricKey, Long> rejections
    ) {
        body.append("# HELP firefly_tenant_admission_rejections_total Quota-aware dispatch admissions rejected.\n")
                .append("# TYPE firefly_tenant_admission_rejections_total counter\n");
        rejections.entrySet().stream()
                .sorted(java.util.Comparator
                        .comparing((java.util.Map.Entry<SchedulerMetrics.TenantAdmissionMetricKey, Long> entry) ->
                                entry.getKey().tenantId())
                        .thenComparing(entry -> entry.getKey().reason().name()))
                .forEach(entry -> body.append("firefly_tenant_admission_rejections_total{tenant=\"")
                        .append(escapeLabel(entry.getKey().tenantId())).append("\",reason=\"")
                        .append(entry.getKey().reason().name()).append("\"} ")
                        .append(entry.getValue()).append('\n'));
    }

    private void appendTenantCapacityHealthMetrics(StringBuilder body, SchedulerMetrics metrics) {
        appendLowCardinalityCounter(body, "firefly_tenant_capacity_observations_dropped_total",
                "Capacity observations dropped before persistence.", "reason",
                metrics.tenantCapacityObservationsDropped());
        appendLowCardinalityCounter(body, "firefly_tenant_capacity_flush_failures_total",
                "Capacity observation flush failures.", "reason", metrics.tenantCapacityFlushFailures());
        appendLowCardinalityCounter(body, "firefly_tenant_quota_preview_total",
                "Tenant quota previews by overall risk.", "risk", metrics.tenantQuotaPreviews());
        body.append("# HELP firefly_tenant_capacity_flush_lag_seconds Age of the oldest pending capacity bucket.\n")
                .append("# TYPE firefly_tenant_capacity_flush_lag_seconds gauge\n")
                .append("firefly_tenant_capacity_flush_lag_seconds ")
                .append(metrics.tenantCapacityFlushLagSeconds()).append('\n');
    }

    private static void appendLowCardinalityCounter(
            StringBuilder body, String name, String help, String label, java.util.Map<String, Long> values
    ) {
        body.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" counter\n");
        values.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry ->
                body.append(name).append('{').append(label).append("=\"")
                        .append(escapeLabel(entry.getKey())).append("\"} ")
                        .append(entry.getValue()).append('\n'));
    }

    private void appendTenantUsageMetrics(
            StringBuilder body,
            com.firefly.governance.TenantQuotaStore store,
            Instant now
    ) {
        body.append("# HELP firefly_tenant_usage Authoritative tenant resource usage.\n")
                .append("# TYPE firefly_tenant_usage gauge\n")
                .append("# HELP firefly_tenant_quota_limit Configured tenant resource limit; zero means unlimited.\n")
                .append("# TYPE firefly_tenant_quota_limit gauge\n")
                .append("# HELP firefly_tenant_weight Configured weighted-fair scheduling weight.\n")
                .append("# TYPE firefly_tenant_weight gauge\n")
                .append("# HELP firefly_tenant_enabled Whether admission is enabled for the tenant.\n")
                .append("# TYPE firefly_tenant_enabled gauge\n");
        store.list().stream()
                .sorted(java.util.Comparator.comparing(com.firefly.governance.TenantDefinition::tenantId))
                .forEach(definition -> {
                    String tenant = escapeLabel(definition.tenantId());
                    com.firefly.governance.TenantUsage usage = store.usage(definition.tenantId(), now);
                    appendTenantResource(body, tenant, "concurrent",
                            usage.activeExecutions(), definition.maxConcurrent());
                    appendTenantResource(body, tenant, "cpu_millis",
                            usage.reservedCpuMillis(), definition.maxCpuMillis());
                    appendTenantResource(body, tenant, "memory_bytes",
                            usage.reservedMemoryBytes(), definition.maxMemoryBytes());
                    appendTenantResource(body, tenant, "backlog",
                            usage.backlog(), definition.maxBacklog());
                    appendTenantResource(body, tenant, "trigger_rate_per_second",
                            usage.triggersInWindow(), definition.maxTriggerRatePerSecond());
                    body.append("firefly_tenant_weight{tenant=\"").append(tenant).append("\"} ")
                            .append(definition.weight()).append('\n')
                            .append("firefly_tenant_enabled{tenant=\"").append(tenant).append("\"} ")
                            .append(definition.enabled() ? 1 : 0).append('\n');
                });
    }

    private static void appendTenantResource(
            StringBuilder body, String tenant, String resource, long usage, long limit
    ) {
        String labels = "{tenant=\"" + tenant + "\",resource=\"" + resource + "\"} ";
        body.append("firefly_tenant_usage").append(labels).append(usage).append('\n')
                .append("firefly_tenant_quota_limit").append(labels).append(limit).append('\n');
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
