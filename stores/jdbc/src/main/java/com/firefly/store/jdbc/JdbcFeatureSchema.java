package com.firefly.store.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Installs additive scheduling-semantics and batch tables without changing the v12 compatibility contract. */
public final class JdbcFeatureSchema {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.ofEntries(
            Map.entry("firefly_calendar", Set.of(
                    "calendar_id", "calendar_version", "zone_id", "working_days", "holidays", "extra_working_days"
            )),
            Map.entry("firefly_calendar_metadata", Set.of(
                    "calendar_id", "calendar_version", "jurisdiction", "published_at", "published_by"
            )),
            Map.entry("firefly_calendar_date_rule", Set.of(
                    "calendar_id", "calendar_version", "rule_date", "rule_kind", "rule_name", "source",
                    "locked", "import_id", "reason"
            )),
            Map.entry("firefly_calendar_import", Set.of(
                    "import_id", "calendar_id", "target_version", "provider_id", "provider_version",
                    "jurisdiction", "zone_id", "range_start", "range_end", "source_uri", "source_checksum",
                    "status", "conflict_count", "imported_at", "imported_by", "published_at"
            )),
            Map.entry("firefly_trigger_inbox", Set.of(
                    "event_id", "event_type", "idempotency_key", "job_id", "payload", "status",
                    "failure_reason", "received_at", "processed_at"
            )),
            Map.entry("firefly_trigger_nonce", Set.of("nonce", "expires_at", "created_at")),
            Map.entry("firefly_batch_execution", Set.of(
                    "root_execution_id", "job_id", "total_shards", "completed_shards", "failed_shards",
                    "retried_shards", "input_records", "output_records", "progress_percent", "sla_status",
                    "started_at", "deadline", "updated_at", "fencing_token"
            )),
            Map.entry("firefly_batch_shard", Set.of(
                    "root_execution_id", "shard_index", "attempt", "status", "input_records",
                    "output_records", "checkpoint_id", "checksum", "error_message", "completed_at"
            )),
            Map.entry("firefly_batch_checkpoint", Set.of(
                    "checkpoint_id", "root_execution_id", "shard_index", "attempt", "location", "checksum",
                    "created_at"
            )),
            Map.entry("firefly_dependency_wait", Set.of(
                    "job_id", "business_time", "wait_attempts", "next_check_at", "updated_at"
            )),
            Map.entry("firefly_condition_state", Set.of(
                    "job_id", "business_time", "status", "reason", "updated_at"
            )),
            Map.entry("firefly_dependency_gate", Set.of(
                    "gate_id", "job_id", "business_time", "next_check_at", "deadline_at", "wait_attempts",
                    "status", "reason", "updated_at"
            )),
            Map.entry("firefly_event_aggregation_lock", Set.of("job_id", "aggregation_key")),
            Map.entry("firefly_event_aggregate", Set.of(
                    "aggregate_id", "job_id", "aggregation_key", "latest_payload", "event_count",
                    "first_received_at", "ready_at", "deadline_at", "status", "claim_owner", "claim_until",
                    "updated_at"
            )),
            Map.entry("firefly_event_aggregate_member", Set.of("idempotency_key", "aggregate_id")),
            Map.entry("firefly_backfill_operation", Set.of(
                    "request_id", "job_id", "root_execution_id", "from_inclusive", "to_inclusive",
                    "max_executions", "batch_size", "rate_limit_per_second", "canary_percent",
                    "canary_executions", "status", "canary_active", "cursor_position", "expanded", "dispatched",
                    "failed", "definition_snapshot", "next_allowed_at", "claim_owner", "claim_until", "version",
                    "created_at", "updated_at"
            )),
            Map.entry("firefly_backfill_item", Set.of(
                    "request_id", "ordinal", "fire_time", "execution_id", "status", "error_message", "updated_at"
            )),
            Map.entry("firefly_tenant_policy", Set.of(
                    "tenant_id", "enabled", "weight", "max_concurrent", "max_cpu_millis",
                    "max_memory_bytes", "max_backlog", "max_trigger_rate", "version", "updated_at"
            )),
            Map.entry("firefly_tenant_usage", Set.of(
                    "tenant_id", "active_executions", "reserved_cpu_millis", "reserved_memory_bytes",
                    "backlog", "trigger_window_started_at", "triggers_in_window", "version"
            )),
            Map.entry("firefly_tenant_reservation", Set.of(
                    "reservation_id", "tenant_id", "concurrent_executions", "cpu_millis", "memory_bytes",
                    "backlog", "triggers", "created_at", "expires_at"
            )),
            Map.entry("firefly_dispatch_fairness_state", Set.of(
                    "state_key", "virtual_finish", "updated_at"
            )),
            Map.entry("firefly_tenant_capacity_sample", Set.of(
                    "tenant_id", "bucket_start", "sample_count", "min_policy_version", "max_policy_version",
                    "min_runtime_version", "max_runtime_version", "max_active_executions",
                    "max_reserved_cpu_millis", "max_reserved_memory_bytes", "max_backlog", "max_trigger_rate",
                    "saturated_concurrency_samples", "saturated_cpu_samples", "saturated_memory_samples",
                    "saturated_backlog_samples", "saturated_trigger_rate_samples", "rejected_concurrency",
                    "rejected_cpu", "rejected_memory", "rejected_backlog", "rejected_trigger_rate",
                    "rejected_disabled", "created_at", "updated_at"
            )),
            Map.entry("firefly_tenant_capacity_flush", Set.of(
                    "flush_id", "bucket_start", "created_at"
            ))
    );

    private JdbcFeatureSchema() { }

    public static void initialize(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            JdbcDialect dialect = JdbcDialect.resolve(connection, options);
            JdbcSchema.acquireMigrationLock(statement, dialect);
            try {
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 13)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 14)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 15)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 16)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 17)) statement.execute(sql);
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 18)) statement.execute(sql);
            JdbcSchema.ensureColumn(connection, "firefly_trigger_inbox", "job_id",
                    "varchar(128) not null default ''");
            JdbcSchema.ensureColumn(connection, "firefly_trigger_inbox", "failure_reason",
                    "varchar(4096) not null default ''");
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 19)) {
                // MySQL lacks IF NOT EXISTS for ADD COLUMN/CREATE INDEX. The one-shot
                // external migration contains those statements, while automatic startup
                // uses metadata-driven ensureColumn/ensureIndex and only replays CREATE TABLE.
                if (dialect != JdbcDialect.MYSQL || sql.stripLeading().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("create table if not exists")) {
                    statement.execute(sql);
                }
            }
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 20)) {
                String normalized = sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
                if (dialect == JdbcDialect.MYSQL && (normalized.startsWith("alter table")
                        || normalized.startsWith("create index"))) continue;
                statement.execute(sql);
            }
            for (String sql : JdbcSchemaScript.loadMigration(dialect, 21)) {
                String normalized = sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
                if (dialect == JdbcDialect.MYSQL && normalized.startsWith("create index")) continue;
                statement.execute(sql);
            }
            JdbcSchema.ensureColumn(connection, "firefly_dispatch_outbox", "tenant_id",
                    "varchar(128) not null default 'default'");
            JdbcSchema.ensureColumn(connection, "firefly_dispatch_outbox", "queue_priority",
                    "integer not null default 0");
            JdbcSchema.ensureIndex(connection, "firefly_calendar_date_rule", "idx_firefly_calendar_rule_date", "calendar_id, calendar_version, rule_date");
            JdbcSchema.ensureIndex(connection, "firefly_calendar_import", "idx_firefly_calendar_import_status", "calendar_id, status, imported_at");
            JdbcSchema.ensureIndex(connection, "firefly_dependency_wait", "idx_firefly_dependency_wait_next", "next_check_at, job_id");
            JdbcSchema.ensureIndex(connection, "firefly_dependency_gate", "idx_firefly_dependency_gate_due", "status, next_check_at, job_id");
            JdbcSchema.ensureIndex(connection, "firefly_event_aggregate", "idx_firefly_event_aggregate_due",
                    "status, ready_at, deadline_at, claim_until, aggregate_id");
            JdbcSchema.ensureIndex(connection, "firefly_backfill_operation", "idx_firefly_backfill_runnable",
                    "status, next_allowed_at, claim_until, created_at");
            JdbcSchema.ensureIndex(connection, "firefly_backfill_item", "idx_firefly_backfill_item_execution",
                    "execution_id");
            JdbcSchema.ensureIndex(connection, "firefly_trigger_inbox", "idx_firefly_trigger_failed",
                    "status, received_at, idempotency_key");
            JdbcSchema.ensureIndex(connection, "firefly_trigger_nonce", "idx_firefly_trigger_nonce_expiry",
                    "expires_at, nonce");
            JdbcSchema.ensureIndex(connection, "firefly_execution", "idx_firefly_execution_job_page",
                    "job_id, created_at, execution_id");
            JdbcSchema.ensureIndex(connection, "firefly_execution", "idx_firefly_execution_status_page",
                    "status, created_at, execution_id");
            JdbcSchema.ensureIndex(connection, "firefly_audit_log", "idx_firefly_audit_filter_page",
                    "resource_type, outcome, occurred_at, audit_id");
            JdbcSchema.ensureIndex(connection, "firefly_audit_log", "idx_firefly_audit_actor_page",
                    "actor, occurred_at, audit_id");
            JdbcSchema.ensureIndex(connection, "firefly_audit_log", "idx_firefly_audit_action_page",
                    "action_name, occurred_at, audit_id");
            JdbcSchema.ensureIndex(connection, "firefly_audit_log", "idx_firefly_audit_outcome_page",
                    "outcome, occurred_at, audit_id");
            JdbcSchema.ensureIndex(connection, "firefly_job_history", "idx_firefly_job_history_job",
                    "job_id, occurred_at, history_id");
            JdbcSchema.ensureIndex(connection, "firefly_job_history", "idx_firefly_job_history_filter_page",
                    "job_id, action_name, occurred_at, history_id");
            JdbcSchema.ensureIndex(connection, "firefly_backfill_operation", "idx_firefly_backfill_page",
                    "job_id, status, created_at, request_id");
            JdbcSchema.ensureIndex(connection, "firefly_backfill_operation", "idx_firefly_backfill_status_page",
                    "status, created_at, request_id");
            JdbcSchema.ensureIndex(connection, "firefly_dispatch_outbox", "idx_firefly_outbox_dead_page",
                    "status, job_id, available_at, outbox_id");
            JdbcSchema.ensureIndex(connection, "firefly_dispatch_outbox", "idx_firefly_outbox_dead_type_page",
                    "status, dispatch_type, available_at, outbox_id");
            JdbcSchema.ensureIndex(connection, "firefly_tenant_reservation",
                    "idx_firefly_tenant_reservation_expiry", "expires_at, reservation_id");
            JdbcSchema.ensureIndex(connection, "firefly_tenant_reservation",
                    "idx_firefly_tenant_reservation_tenant_expiry", "tenant_id, expires_at, reservation_id");
            JdbcSchema.ensureIndex(connection, "firefly_dispatch_outbox",
                    "idx_firefly_outbox_tenant_claim",
                    "dispatch_type, status, tenant_id, available_at, outbox_id");
            JdbcSchema.ensureIndex(connection, "firefly_tenant_capacity_sample",
                    "idx_firefly_capacity_bucket_tenant", "bucket_start, tenant_id");
            JdbcSchema.ensureIndex(connection, "firefly_tenant_capacity_flush",
                    "idx_firefly_capacity_flush_bucket", "bucket_start, flush_id");
            } finally {
                JdbcSchema.releaseMigrationLock(statement, dialect);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to initialize feature schema", e);
        }
    }

    /** Validates additive feature migrations when startup is configured for externally managed schema changes. */
    public static void validate(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        try (Connection connection = dataSource.getConnection()) {
            JdbcDialect.resolve(connection, options);
            var metadata = connection.getMetaData();
            for (Map.Entry<String, Set<String>> table : REQUIRED_COLUMNS.entrySet()) {
                JdbcSchema.validateTable(metadata, table.getKey(), table.getValue());
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to validate feature schema", e);
        }
    }
}
