package com.firefly.store.jdbc;

import com.firefly.cluster.ShardHasher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit offline maintenance operation for changing the immutable scheduler shard count. */
public final class JdbcReshardTool {
    private JdbcReshardTool() {
    }

    public static ReshardResult reshard(DataSource dataSource, JdbcSchemaOptions options, boolean confirmed) {
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
                    ensureNoOnlineNodes(connection);
                    ensureNoActiveExecutions(connection);
                    ensureNoActiveOutboxRows(connection);
                    int currentShardCount = readShardCount(connection);
                    if (currentShardCount == options.schedulerShardCount()) {
                        connection.commit();
                        return new ReshardResult(currentShardCount, options.schedulerShardCount(), 0, 0);
                    }
                    List<String> jobIds = jobIds(connection);
                    int affectedJobs = 0;
                    try (PreparedStatement update = connection.prepareStatement(
                            "update firefly_job set shard_id=? where job_id=?")) {
                        for (String jobId : jobIds) {
                            update.setInt(1, ShardHasher.shardFor(jobId, options.schedulerShardCount()));
                            update.setString(2, jobId);
                            update.addBatch();
                        }
                        for (int count : update.executeBatch()) {
                            if (count > 0) affectedJobs += count;
                        }
                    }
                    long revision = readRevision(connection);
                    try (PreparedStatement metadata = connection.prepareStatement("""
                            update firefly_cluster_metadata
                            set metadata_value=?, updated_at=current_timestamp
                            where metadata_key='scheduler.shard-count'
                            """)) {
                        metadata.setString(1, Integer.toString(options.schedulerShardCount()));
                        if (metadata.executeUpdate() != 1) {
                            throw new JdbcException("scheduler.shard-count metadata is missing");
                        }
                    }
                    try (PreparedStatement metadata = connection.prepareStatement("""
                            update firefly_cluster_metadata
                            set metadata_value=?, updated_at=current_timestamp
                            where metadata_key='jobs.revision'
                            """)) {
                        metadata.setString(1, Long.toString(revision + 1));
                        if (metadata.executeUpdate() != 1) {
                            throw new JdbcException("jobs.revision metadata is missing");
                        }
                    }
                    int deletedLeases;
                    try (PreparedStatement leases = connection.prepareStatement("delete from firefly_shard_lease")) {
                        deletedLeases = leases.executeUpdate();
                    }
                    connection.commit();
                    result = new ReshardResult(
                            currentShardCount, options.schedulerShardCount(), affectedJobs, deletedLeases
                    );
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

    private static void ensureNoOnlineNodes(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from firefly_node where status='ONLINE'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getLong(1) > 0) {
                    throw new JdbcException("reshard requires all Firefly nodes to be offline");
                }
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
}
