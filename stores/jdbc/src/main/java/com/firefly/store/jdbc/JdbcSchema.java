package com.firefly.store.jdbc;

import com.firefly.cluster.ShardHasher;
import com.firefly.cluster.SchedulerShardConfig;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Initializes and validates JDBC schema by selecting a dialect-specific SQL resource.
 */
public final class JdbcSchema {
    public static final int CURRENT_VERSION = 11;

    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.ofEntries(
            Map.entry("firefly_schema_version", Set.of("version", "installed_at")),
            Map.entry("firefly_node", Set.of("node_id", "roles", "registered_at", "last_heartbeat_at", "status", "metadata")),
            Map.entry("firefly_shard_lease", Set.of("shard_id", "owner_node_id", "lease_until", "fencing_token")),
            Map.entry("firefly_executor", Set.of("executor_name", "description", "protocols", "metadata", "enabled")),
            Map.entry("firefly_job_group", Set.of("group_id", "group_name", "executor_name", "metadata", "enabled")),
            Map.entry("firefly_execution", Set.of(
                    "execution_id", "job_id", "scheduled_fire_time", "dispatch_time", "dispatch_mode",
                    "completion_policy", "status", "expected_targets", "accepted_targets", "owner_node_id",
                    "fencing_token", "root_execution_id", "run_attempt", "retry_scheduled", "timeout_at",
                    "created_at", "updated_at"
            )),
            Map.entry("firefly_execution_target", Set.of(
                    "target_execution_id", "execution_id", "instance_id", "gateway_node_id", "shard_index",
                    "status", "attempt", "acknowledged_at", "completed_at", "error_message", "created_at", "updated_at"
            )),
            Map.entry("firefly_dispatch_outbox", Set.of(
                    "outbox_id", "execution_id", "job_id", "scheduled_fire_time", "dispatch_time", "status",
                    "attempt", "available_at", "claim_owner", "claim_until", "ack_deadline", "owner_node_id",
                    "fencing_token", "dispatch_type", "snapshot_payload", "root_execution_id", "run_attempt",
                    "last_error", "created_at", "updated_at"
            )),
            Map.entry("firefly_cluster_metadata", Set.of("metadata_key", "metadata_value", "updated_at")),
            Map.entry("firefly_executor_instance_location", Set.of(
                    "executor_name", "instance_id", "gateway_node_id", "gateway_address", "session_id",
                    "status", "last_seen_at", "lease_until", "metadata"
            )),
            Map.entry("firefly_audit_log", Set.of(
                    "audit_id", "occurred_at", "actor", "role_name", "action_name", "resource_type",
                    "resource_id", "outcome", "before_payload", "after_payload", "detail"
            )),
            Map.entry("firefly_job_history", Set.of(
                    "history_id", "job_id", "job_version", "action_name", "actor", "before_payload",
                    "after_payload", "occurred_at"
            )),
            Map.entry("firefly_user", Set.of(
                    "username", "password_hash", "roles", "enabled", "version", "created_at", "updated_at"
            )),
            Map.entry("firefly_integration_key", Set.of(
                    "key_id", "key_hash", "version", "created_at", "updated_at"
            )),
            Map.entry("firefly_job", Set.of(
                    "job_id",
                    "group_id",
                    "job_name",
                    "handler_name",
                    "schedule_type",
                    "schedule_value",
                    "zone_id",
                    "misfire_policy",
                    "misfire_grace",
                    "concurrency_policy",
                    "max_catch_up_count",
                    "timeout_value",
                    "parameters",
                    "shard_id",
                    "dispatch_mode",
                    "routing_strategy",
                    "completion_policy",
                    "shard_count",
                    "routing_key",
                    "retry_scope",
                    "enabled",
                    "next_fire_time",
                    "version"
            ))
    );

    private JdbcSchema() {
    }

    public static void initialize(DataSource dataSource) {
        initialize(dataSource, JdbcSchemaOptions.auto());
    }

    public static void initialize(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        try (Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()) {
            JdbcDialect dialect = JdbcDialect.resolve(connection, options);
            Integer installedShardCount = installedShardCount(connection);
            rejectShardCountMismatch(installedShardCount, options.schedulerShardCount());
            acquireMigrationLock(statement, dialect);
            try {
                // A concurrent initializer may have completed while this node waited for the lock.
                installedShardCount = installedShardCount(connection);
                rejectShardCountMismatch(installedShardCount, options.schedulerShardCount());
                for (String sql : JdbcSchemaScript.load(dialect)) {
                    String normalized = sql.toLowerCase(Locale.ROOT);
                    if (!normalized.contains("idx_firefly_job_shard_due")
                            && !normalized.contains("idx_firefly_execution_timeout")
                            && !normalized.contains("idx_firefly_outbox_role_claim")) {
                        statement.execute(sql);
                    }
                }
                migrateJobDispatchColumns(connection);
                migrateOutboxColumns(connection, dialect);
                migrateExecutionRetryColumns(connection);
                migrateExecutionTimeoutColumn(connection, dialect);
                backfillOutboxSnapshots(connection);
                backfillExecutionTimeouts(connection);
                configureClusterShardCount(connection, installedShardCount, options.schedulerShardCount());
                backfillJobShards(connection, options.schedulerShardCount());
                ensureIndex(connection, "firefly_job", "idx_firefly_job_shard_due",
                        "shard_id, enabled, next_fire_time, job_id");
                ensureIndex(connection, "firefly_execution", "idx_firefly_execution_timeout",
                        "status, timeout_at, execution_id");
                ensureIndex(connection, "firefly_dispatch_outbox", "idx_firefly_outbox_role_claim",
                        "dispatch_type, status, available_at, ack_deadline, claim_until, outbox_id");
                installCurrentVersion(connection);
            } finally {
                releaseMigrationLock(statement, dialect);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to initialize firefly jdbc schema", e);
        }
        validate(dataSource, options);
    }

    public static void initializeIfEmpty(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        // Every statement is idempotent. Re-running it also adds newly introduced optional tables.
        initialize(dataSource, options);
    }

    public static void validate(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        try (Connection connection = dataSource.getConnection()) {
            JdbcDialect.resolve(connection, options);
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, Set<String>> table : REQUIRED_COLUMNS.entrySet()) {
                validateTable(metadata, table.getKey(), table.getValue());
            }
            validateVersion(connection);
            validateClusterMetadata(connection, options.schedulerShardCount());
        } catch (SQLException e) {
            throw new JdbcException("failed to validate firefly jdbc schema", e);
        }
    }

    private static boolean hasAnyFireflyTable(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : REQUIRED_COLUMNS.keySet()) {
                if (tableExists(metadata, table)) {
                    return true;
                }
            }
            return false;
        } catch (SQLException e) {
            throw new JdbcException("failed to inspect firefly jdbc schema", e);
        }
    }

    private static void validateTable(DatabaseMetaData metadata, String tableName, Set<String> requiredColumns) throws SQLException {
        if (!tableExists(metadata, tableName)) {
            throw new JdbcException("missing firefly jdbc table: " + tableName);
        }
        Set<String> actualColumns = columns(metadata, tableName);
        for (String column : requiredColumns) {
            if (!actualColumns.contains(column)) {
                throw new JdbcException("missing firefly jdbc column: " + tableName + "." + column);
            }
        }
    }

    private static boolean tableExists(DatabaseMetaData metadata, String tableName) throws SQLException {
        for (String candidate : tableNameCandidates(tableName)) {
            try (ResultSet resultSet = metadata.getTables(null, null, candidate, new String[]{"TABLE"})) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> columns(DatabaseMetaData metadata, String tableName) throws SQLException {
        java.util.HashSet<String> columns = new java.util.HashSet<>();
        for (String candidate : tableNameCandidates(tableName)) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return Set.copyOf(columns);
    }

    private static void validateVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select version from firefly_schema_version where version = " + CURRENT_VERSION)) {
            if (!resultSet.next()) {
                throw new JdbcException("unsupported firefly jdbc schema version; expected version " + CURRENT_VERSION);
            }
        }
    }

    public static void validateClusterInvariant(DataSource dataSource, JdbcSchemaOptions options) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        try (Connection connection = dataSource.getConnection()) {
            JdbcDialect.resolve(connection, options);
            validateClusterMetadata(connection, options.schedulerShardCount());
        } catch (SQLException e) {
            throw new JdbcException("failed to validate firefly cluster metadata", e);
        }
    }

    private static Integer installedShardCount(Connection connection) throws SQLException {
        if (!tableExists(connection.getMetaData(), "firefly_cluster_metadata")) return null;
        try (PreparedStatement statement = connection.prepareStatement("""
                select metadata_value from firefly_cluster_metadata
                where metadata_key='scheduler.shard-count'
                """); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? Integer.parseInt(resultSet.getString(1)) : null;
        }
    }

    private static void configureClusterShardCount(
            Connection connection, Integer installedShardCount, int requestedShardCount
    ) throws SQLException {
        rejectShardCountMismatch(installedShardCount, requestedShardCount);
        if (installedShardCount != null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_cluster_metadata
                set metadata_value=?, updated_at=current_timestamp
                where metadata_key='scheduler.shard-count'
                """)) {
            statement.setString(1, Integer.toString(requestedShardCount));
            if (statement.executeUpdate() == 0) {
                throw new JdbcException("cluster metadata scheduler.shard-count is missing after initialization");
            }
        }
    }

    private static void rejectShardCountMismatch(Integer installedShardCount, int requestedShardCount) {
        if (installedShardCount != null && installedShardCount != requestedShardCount) {
            throw new JdbcException("cluster scheduler.shard-count is immutable; installed="
                    + installedShardCount + ", requested=" + requestedShardCount);
        }
    }

    private static void validateClusterMetadata(Connection connection, int expectedShardCount) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                select metadata_value from firefly_cluster_metadata where metadata_key='scheduler.shard-count'
                """); ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next() || Integer.parseInt(resultSet.getString(1)) != expectedShardCount) {
                throw new JdbcException("cluster scheduler.shard-count does not match runtime value "
                        + expectedShardCount);
            }
        }
        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                select metadata_value from firefly_cluster_metadata where metadata_key='jobs.revision'
                """); ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new JdbcException("cluster metadata jobs.revision is missing");
            }
            Long.parseLong(resultSet.getString(1));
        }
    }

    static void acquireMigrationLock(Statement statement, JdbcDialect dialect) throws SQLException {
        switch (dialect) {
            case POSTGRESQL -> statement.execute("select pg_advisory_lock(704926301)");
            case MYSQL -> {
                try (ResultSet resultSet = statement.executeQuery("select get_lock('firefly_schema_migration', 30)")) {
                    if (!resultSet.next() || resultSet.getInt(1) != 1) {
                        throw new JdbcException("timed out acquiring Firefly schema migration lock");
                    }
                }
            }
            case H2 -> {
                // H2 is used for embedded and tests; one process owns schema initialization.
            }
        }
    }

    static void releaseMigrationLock(Statement statement, JdbcDialect dialect) throws SQLException {
        switch (dialect) {
            case POSTGRESQL -> statement.execute("select pg_advisory_unlock(704926301)");
            case MYSQL -> statement.execute("select release_lock('firefly_schema_migration')");
            case H2 -> { }
        }
    }

    private static void migrateJobDispatchColumns(Connection connection) throws SQLException {
        Set<String> existingColumns = columns(connection.getMetaData(), "firefly_job");
        List<ColumnMigration> migrations = List.of(
                new ColumnMigration("dispatch_mode", "varchar(32) not null default 'UNICAST'"),
                new ColumnMigration("routing_strategy", "varchar(32) not null default 'ROUND_ROBIN'"),
                new ColumnMigration("completion_policy", "varchar(32) not null default 'ALL_SUCCESS'"),
                new ColumnMigration("shard_count", "integer not null default 1"),
                new ColumnMigration("routing_key", "varchar(512) not null default ''"),
                new ColumnMigration("retry_scope", "varchar(32) not null default 'FAILED_TARGETS_ONLY'"),
                new ColumnMigration("shard_id", "integer not null default 0")
        );
        try (Statement statement = connection.createStatement()) {
            for (ColumnMigration migration : migrations) {
                if (!existingColumns.contains(migration.name())) {
                    statement.execute("alter table firefly_job add column "
                            + migration.name() + " " + migration.definition());
                }
            }
        }
    }

    private static void migrateOutboxColumns(Connection connection, JdbcDialect dialect) throws SQLException {
        Set<String> existingColumns = columns(connection.getMetaData(), "firefly_dispatch_outbox");
        try (Statement statement = connection.createStatement()) {
            if (!existingColumns.contains("dispatch_type")) {
                statement.execute("alter table firefly_dispatch_outbox add column "
                        + "dispatch_type varchar(16) not null default 'REMOTE'");
            }
            if (!existingColumns.contains("snapshot_payload")) {
                String type = dialect == JdbcDialect.H2 ? "clob" : "text";
                statement.execute("alter table firefly_dispatch_outbox add column snapshot_payload " + type);
            }
            if (!existingColumns.contains("root_execution_id")) {
                statement.execute("alter table firefly_dispatch_outbox add column root_execution_id varchar(256)");
                statement.execute("update firefly_dispatch_outbox set root_execution_id=execution_id where root_execution_id is null");
            }
            if (!existingColumns.contains("run_attempt")) {
                statement.execute("alter table firefly_dispatch_outbox add column run_attempt integer not null default 0");
            }
        }
    }

    private static void migrateExecutionRetryColumns(Connection connection) throws SQLException {
        Set<String> columns = columns(connection.getMetaData(), "firefly_execution");
        try (Statement statement = connection.createStatement()) {
            if (!columns.contains("root_execution_id")) {
                statement.execute("alter table firefly_execution add column root_execution_id varchar(256)");
                statement.execute("update firefly_execution set root_execution_id=execution_id where root_execution_id is null");
            }
            if (!columns.contains("run_attempt")) {
                statement.execute("alter table firefly_execution add column run_attempt integer not null default 0");
            }
            if (!columns.contains("retry_scheduled")) {
                statement.execute("alter table firefly_execution add column retry_scheduled boolean not null default false");
            }
        }
    }

    private static void migrateExecutionTimeoutColumn(Connection connection, JdbcDialect dialect) throws SQLException {
        Set<String> columns = columns(connection.getMetaData(), "firefly_execution");
        if (columns.contains("timeout_at")) return;
        String type = switch (dialect) {
            case POSTGRESQL -> "timestamp with time zone";
            case MYSQL -> "timestamp(6)";
            case H2 -> "timestamp";
        };
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table firefly_execution add column timeout_at " + type);
        }
    }

    private static void backfillExecutionTimeouts(Connection connection) throws SQLException {
        Instant databaseNow = JdbcTimeSource.database().now(connection);
        try (PreparedStatement select = connection.prepareStatement("""
                select execution.execution_id, execution.dispatch_time, job.timeout_value
                from firefly_execution execution
                left join firefly_job job on job.job_id=execution.job_id
                where execution.status in ('DISPATCHING','DISPATCHED','RUNNING')
                  and execution.timeout_at is null
                """);
             ResultSet resultSet = select.executeQuery();
             PreparedStatement update = connection.prepareStatement("""
                     update firefly_execution set timeout_at=? where execution_id=? and timeout_at is null
                     """)) {
            while (resultSet.next()) {
                Timestamp dispatchTime = resultSet.getTimestamp("dispatch_time");
                String timeoutValue = resultSet.getString("timeout_value");
                Instant timeoutAt = databaseNow;
                if (dispatchTime != null && timeoutValue != null && !timeoutValue.isBlank()) {
                    try {
                        timeoutAt = dispatchTime.toInstant().plus(Duration.parse(timeoutValue));
                    } catch (RuntimeException ignored) {
                        // Invalid legacy timeout values expire immediately instead of becoming immortal work.
                    }
                }
                update.setTimestamp(1, Timestamp.from(timeoutAt));
                update.setString(2, resultSet.getString("execution_id"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void backfillOutboxSnapshots(Connection connection) throws SQLException {
        try (java.sql.PreparedStatement select = connection.prepareStatement("""
                select outbox_id, job_id from firefly_dispatch_outbox
                where status not in ('DONE','DEAD')
                  and (snapshot_payload is null or length(snapshot_payload) = 0)
                """); ResultSet resultSet = select.executeQuery();
             java.sql.PreparedStatement update = connection.prepareStatement("""
                update firefly_dispatch_outbox set dispatch_type=?, snapshot_payload=? where outbox_id=?
                """);
             java.sql.PreparedStatement markDead = connection.prepareStatement("""
                update firefly_dispatch_outbox set status='DEAD',
                    last_error='job definition missing during schema v5 migration', updated_at=current_timestamp
                where outbox_id=?
                """)) {
            while (resultSet.next()) {
                try {
                    com.firefly.domain.JobDefinition definition = JdbcJobRepository.findDefinitionForMigration(
                            connection, resultSet.getString("job_id")
                    );
                    update.setString(1, JdbcJobRepository.dispatchTypeForMigration(definition));
                    update.setString(2, JdbcJobRepository.encodeSnapshotForMigration(definition));
                    update.setString(3, resultSet.getString("outbox_id"));
                    update.addBatch();
                } catch (JdbcException missingJob) {
                    markDead.setString(1, resultSet.getString("outbox_id"));
                    markDead.addBatch();
                }
            }
            update.executeBatch();
            markDead.executeBatch();
        }
    }

    private static void installCurrentVersion(Connection connection) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_schema_version (version, installed_at)
                select ?, current_timestamp
                where not exists (select 1 from firefly_schema_version where version = ?)
                """)) {
            statement.setInt(1, CURRENT_VERSION);
            statement.setInt(2, CURRENT_VERSION);
            statement.executeUpdate();
        }
    }

    private static void ensureIndex(Connection connection, String table, String index, String columns) throws SQLException {
        boolean exists = false;
        for (String candidate : tableNameCandidates(table)) {
            try (ResultSet resultSet = connection.getMetaData().getIndexInfo(null, null, candidate, false, false)) {
                while (resultSet.next()) {
                    String name = resultSet.getString("INDEX_NAME");
                    if (name != null && name.equalsIgnoreCase(index)) {
                        exists = true;
                        break;
                    }
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create index " + index + " on " + table + " (" + columns + ")");
            }
        }
    }

    private static void backfillJobShards(Connection connection, int shardCount) throws SQLException {
        try (Statement query = connection.createStatement();
             ResultSet resultSet = query.executeQuery("select job_id, shard_id from firefly_job");
             java.sql.PreparedStatement update = connection.prepareStatement(
                     "update firefly_job set shard_id = ? where job_id = ? and shard_id <> ?")) {
            while (resultSet.next()) {
                String jobId = resultSet.getString("job_id");
                int shardId = ShardHasher.shardFor(jobId, shardCount);
                update.setInt(1, shardId);
                update.setString(2, jobId);
                update.setInt(3, shardId);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static List<String> tableNameCandidates(String tableName) {
        return List.of(tableName, tableName.toUpperCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT));
    }

    private record ColumnMigration(String name, String definition) {
    }
}
