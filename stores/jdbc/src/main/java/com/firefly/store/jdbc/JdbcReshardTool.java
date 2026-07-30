package com.firefly.store.jdbc;

import com.firefly.cluster.NodeRole;
import com.firefly.cluster.ShardHasher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Explicit maintenance operations for changing the scheduler shard count. */
public final class JdbcReshardTool {
    private JdbcReshardTool() {
    }

    public static ReshardResult reshard(DataSource dataSource, JdbcSchemaOptions options, boolean confirmed) {
        return changeShardCount(dataSource, options, confirmed, ReshardMode.OFFLINE);
    }

    /**
     * Expands scheduler shards while data-plane-only Gateway and Executor nodes remain online.
     * Scheduler, Standby, and API nodes must be fully drained and offline first.
     */
    public static ReshardResult expandOnline(
            DataSource dataSource,
            JdbcSchemaOptions options,
            boolean confirmed
    ) {
        return changeShardCount(dataSource, options, confirmed, ReshardMode.ONLINE_EXPANSION);
    }

    private static ReshardResult changeShardCount(
            DataSource dataSource,
            JdbcSchemaOptions options,
            boolean confirmed,
            ReshardMode mode
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(options, "options");
        if (!confirmed) {
            throw new IllegalArgumentException("reshard requires explicit confirmation");
        }
        ReshardResult result;
        try (Connection connection = dataSource.getConnection()) {
            JdbcDialect dialect = JdbcDialect.resolve(connection, options);
            connection.setAutoCommit(false);
            try (var lock = connection.createStatement()) {
                JdbcSchema.acquireMigrationLock(lock, dialect);
                try {
                    int currentShardCount = readShardCount(connection);
                    if (mode == ReshardMode.ONLINE_EXPANSION) {
                        if (options.schedulerShardCount() < currentShardCount) {
                            throw new JdbcException("online reshard only supports shard expansion");
                        }
                        ensureNoActiveShardAwareNodes(connection);
                    } else {
                        ensureAllNodesOffline(connection);
                    }
                    ensureNoActiveExecutions(connection);
                    ensureNoActiveOutboxRows(connection);
                    if (currentShardCount == options.schedulerShardCount()) {
                        connection.commit();
                        return new ReshardResult(currentShardCount, options.schedulerShardCount(), 0, 0);
                    }
                    result = applyShardCountChange(
                            connection, currentShardCount, options.schedulerShardCount()
                    );
                    connection.commit();
                } finally {
                    JdbcSchema.releaseMigrationLock(lock, dialect);
                }
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to reshard Firefly scheduler jobs", e);
        }
        JdbcSchema.validateClusterInvariant(dataSource, options);
        return result;
    }

    private static ReshardResult applyShardCountChange(
            Connection connection,
            int currentShardCount,
            int targetShardCount
    ) throws SQLException {
        List<String> jobIds = jobIds(connection);
        int affectedJobs = 0;
        try (PreparedStatement update = connection.prepareStatement(
                "update firefly_job set shard_id=? where job_id=?")) {
            for (String jobId : jobIds) {
                update.setInt(1, ShardHasher.shardFor(jobId, targetShardCount));
                update.setString(2, jobId);
                update.addBatch();
            }
            for (int count : update.executeBatch()) {
                if (count > 0) affectedJobs += count;
            }
        }
        updateMetadata(connection, "scheduler.shard-count", Integer.toString(targetShardCount));
        updateMetadata(connection, "jobs.revision", Long.toString(readRevision(connection) + 1));
        int deletedLeases;
        try (PreparedStatement leases = connection.prepareStatement("delete from firefly_shard_lease")) {
            deletedLeases = leases.executeUpdate();
        }
        return new ReshardResult(currentShardCount, targetShardCount, affectedJobs, deletedLeases);
    }

    private static void updateMetadata(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement metadata = connection.prepareStatement("""
                update firefly_cluster_metadata
                set metadata_value=?, updated_at=current_timestamp
                where metadata_key=?
                """)) {
            metadata.setString(1, value);
            metadata.setString(2, key);
            if (metadata.executeUpdate() != 1) {
                throw new JdbcException(key + " metadata is missing");
            }
        }
    }

    private static void ensureAllNodesOffline(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from firefly_node where status <> 'OFFLINE'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getLong(1) > 0) {
                    throw new JdbcException("reshard requires all Firefly nodes to be offline");
                }
            }
        }
    }

    private static void ensureNoActiveShardAwareNodes(Connection connection) throws SQLException {
        Set<NodeRole> shardAwareRoles = EnumSet.of(NodeRole.SCHEDULER, NodeRole.STANDBY, NodeRole.API);
        try (PreparedStatement statement = connection.prepareStatement("""
                select node_id, roles, status from firefly_node where status <> 'OFFLINE'
                """); ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                Set<NodeRole> roles = JdbcEncoding.decodeRoles(resultSet.getString("roles"));
                if (roles.stream().noneMatch(shardAwareRoles::contains)) continue;
                throw new JdbcException(
                        "online reshard requires shard-aware node '" + resultSet.getString("node_id")
                                + "' to be offline; status=" + resultSet.getString("status")
                                + ", roles=" + roles
                );
            }
        }
    }

    private static void ensureNoActiveExecutions(Connection connection) throws SQLException {
        long active = count(connection, """
                select count(*) from firefly_execution
                where status not in ('SUCCEEDED', 'PARTIAL', 'FAILED', 'TIMEOUT')
                """);
        if (active > 0) {
            throw new JdbcException("reshard requires no active executions; found " + active);
        }
    }

    private static void ensureNoActiveOutboxRows(Connection connection) throws SQLException {
        long active = count(connection, """
                select count(*) from firefly_dispatch_outbox
                where status not in ('DONE', 'DEAD')
                """);
        if (active > 0) {
            throw new JdbcException("reshard requires no active dispatch outbox rows; found " + active);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static int readShardCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select metadata_value from firefly_cluster_metadata
                where metadata_key='scheduler.shard-count'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new JdbcException("scheduler.shard-count metadata is missing");
                return Integer.parseInt(resultSet.getString(1));
            }
        }
    }

    private static long readRevision(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select metadata_value from firefly_cluster_metadata
                where metadata_key='jobs.revision'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new JdbcException("jobs.revision metadata is missing");
                return Long.parseLong(resultSet.getString(1));
            }
        }
    }

    private static List<String> jobIds(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("select job_id from firefly_job");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) result.add(resultSet.getString(1));
        }
        return List.copyOf(result);
    }

    public record ReshardResult(int oldShardCount, int newShardCount, int affectedJobs, int deletedLeases) {
    }

    private enum ReshardMode {
        OFFLINE,
        ONLINE_EXPANSION
    }
}
