package com.firefly.store.jdbc;

import com.firefly.executor.ExecutorInstanceDirectory;
import com.firefly.executor.ExecutorInstanceLocation;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcExecutorInstanceDirectory implements ExecutorInstanceDirectory {
    private final DataSource dataSource;

    public JdbcExecutorInstanceDirectory(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void register(ExecutorInstanceLocation location) {
        try (var connection = dataSource.getConnection()) {
            if (update(connection, location) == 0) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into firefly_executor_instance_location
                        (executor_name, instance_id, gateway_node_id, gateway_address, session_id,
                         status, last_seen_at, lease_until, metadata)
                        values (?, ?, ?, ?, ?, 'ONLINE', ?, ?, ?)
                        """)) {
                    bind(statement, location, false);
                    statement.executeUpdate();
                } catch (SQLException race) {
                    if (update(connection, location) == 0) throw race;
                }
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to register executor instance location", e);
        }
    }

    @Override
    public boolean heartbeat(
            String executorName, String instanceId, String gatewayNodeId,
            String sessionId, Instant now, Duration leaseDuration
    ) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_executor_instance_location
                     set last_seen_at=?, lease_until=?, status='ONLINE'
                     where executor_name=? and instance_id=? and gateway_node_id=? and session_id=?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now.plus(leaseDuration)));
            statement.setString(3, executorName);
            statement.setString(4, instanceId);
            statement.setString(5, gatewayNodeId);
            statement.setString(6, sessionId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to heartbeat executor instance location", e);
        }
    }

    @Override
    public boolean markOffline(String executorName, String instanceId, String gatewayNodeId, String sessionId) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_executor_instance_location set status='OFFLINE', lease_until=last_seen_at
                     where executor_name=? and instance_id=? and gateway_node_id=? and session_id=?
                     """)) {
            statement.setString(1, executorName);
            statement.setString(2, instanceId);
            statement.setString(3, gatewayNodeId);
            statement.setString(4, sessionId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to mark executor instance location offline", e);
        }
    }

    @Override
    public List<ExecutorInstanceLocation> listOnline(String executorName, Instant now) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select executor_name, instance_id, gateway_node_id, gateway_address,
                            session_id, last_seen_at, lease_until, metadata
                     from firefly_executor_instance_location
                     where executor_name=? and status='ONLINE' and lease_until>?
                     order by instance_id
                     """)) {
            statement.setString(1, executorName);
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutorInstanceLocation> locations = new ArrayList<>();
                while (resultSet.next()) {
                    locations.add(new ExecutorInstanceLocation(
                            resultSet.getString("executor_name"), resultSet.getString("instance_id"),
                            resultSet.getString("gateway_node_id"), resultSet.getString("gateway_address"),
                            resultSet.getString("session_id"), resultSet.getTimestamp("last_seen_at").toInstant(),
                            resultSet.getTimestamp("lease_until").toInstant(),
                            JdbcEncoding.decodeMap(resultSet.getString("metadata"))
                    ));
                }
                return List.copyOf(locations);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list executor instance locations", e);
        }
    }

    @Override
    public java.util.Optional<ExecutorInstanceLocation> findOnlineInstance(String instanceId, Instant now) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select executor_name, instance_id, gateway_node_id, gateway_address,
                            session_id, last_seen_at, lease_until, metadata
                     from firefly_executor_instance_location
                     where instance_id=? and status='ONLINE' and lease_until>?
                     order by last_seen_at desc
                     """)) {
            statement.setString(1, instanceId);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setMaxRows(1);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new ExecutorInstanceLocation(
                        resultSet.getString("executor_name"), resultSet.getString("instance_id"),
                        resultSet.getString("gateway_node_id"), resultSet.getString("gateway_address"),
                        resultSet.getString("session_id"), resultSet.getTimestamp("last_seen_at").toInstant(),
                        resultSet.getTimestamp("lease_until").toInstant(),
                        JdbcEncoding.decodeMap(resultSet.getString("metadata"))
                ));
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find executor instance location", e);
        }
    }

    private int update(java.sql.Connection connection, ExecutorInstanceLocation location) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_executor_instance_location
                set gateway_node_id=?, gateway_address=?, session_id=?, status='ONLINE',
                    last_seen_at=?, lease_until=?, metadata=?
                where executor_name=? and instance_id=?
                """)) {
            bind(statement, location, true);
            return statement.executeUpdate();
        }
    }

    private void bind(PreparedStatement statement, ExecutorInstanceLocation location, boolean update) throws SQLException {
        if (update) {
            statement.setString(1, location.gatewayNodeId());
            statement.setString(2, location.gatewayAddress());
            statement.setString(3, location.sessionId());
            statement.setTimestamp(4, Timestamp.from(location.lastSeenAt()));
            statement.setTimestamp(5, Timestamp.from(location.leaseUntil()));
            statement.setString(6, JdbcEncoding.encodeMap(location.metadata()));
            statement.setString(7, location.executorName());
            statement.setString(8, location.instanceId());
        } else {
            statement.setString(1, location.executorName());
            statement.setString(2, location.instanceId());
            statement.setString(3, location.gatewayNodeId());
            statement.setString(4, location.gatewayAddress());
            statement.setString(5, location.sessionId());
            statement.setTimestamp(6, Timestamp.from(location.lastSeenAt()));
            statement.setTimestamp(7, Timestamp.from(location.leaseUntil()));
            statement.setString(8, JdbcEncoding.encodeMap(location.metadata()));
        }
    }
}
