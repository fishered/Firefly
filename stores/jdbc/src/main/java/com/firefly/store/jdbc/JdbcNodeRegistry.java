package com.firefly.store.jdbc;

import com.firefly.cluster.FireflyNode;
import com.firefly.cluster.NodeRegistry;
import com.firefly.cluster.NodeStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC-backed node registry for HA deployments.
 */
public final class JdbcNodeRegistry implements NodeRegistry {
    private final DataSource dataSource;

    public JdbcNodeRegistry(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void register(FireflyNode node) {
        Objects.requireNonNull(node, "node");
        try (Connection connection = dataSource.getConnection()) {
            int updated = updateNode(connection, node);
            if (updated == 0) {
                insertNode(connection, node);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to register firefly node", e);
        }
    }

    @Override
    public Optional<FireflyNode> find(String nodeId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select node_id, roles, registered_at, last_heartbeat_at, status, metadata
                     from firefly_node
                     where node_id = ?
                     """)) {
            statement.setString(1, nodeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapNode(resultSet));
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find firefly node", e);
        }
    }

    @Override
    public boolean heartbeat(String nodeId, Instant heartbeatAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_node
                     set last_heartbeat_at = ?, status = ?
                     where node_id = ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(heartbeatAt));
            statement.setString(2, NodeStatus.ONLINE.name());
            statement.setString(3, nodeId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to heartbeat firefly node", e);
        }
    }

    @Override
    public boolean markOffline(String nodeId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_node
                     set status = ?
                     where node_id = ?
                     """)) {
            statement.setString(1, NodeStatus.OFFLINE.name());
            statement.setString(2, nodeId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to mark firefly node offline", e);
        }
    }

    @Override
    public List<FireflyNode> listOnline(Instant now, Duration heartbeatTimeout) {
        Instant oldestAllowedHeartbeat = now.minus(heartbeatTimeout);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select node_id, roles, registered_at, last_heartbeat_at, status, metadata
                     from firefly_node
                     where status = ? and last_heartbeat_at >= ?
                     """)) {
            statement.setString(1, NodeStatus.ONLINE.name());
            statement.setTimestamp(2, Timestamp.from(oldestAllowedHeartbeat));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FireflyNode> nodes = new ArrayList<>();
                while (resultSet.next()) {
                    nodes.add(mapNode(resultSet));
                }
                return nodes.stream()
                        .sorted(Comparator.comparing(FireflyNode::nodeId))
                        .toList();
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list online firefly nodes", e);
        }
    }

    private int updateNode(Connection connection, FireflyNode node) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_node
                set roles = ?, registered_at = ?, last_heartbeat_at = ?, status = ?, metadata = ?
                where node_id = ?
                """)) {
            bindNodeForUpdate(statement, node);
            return statement.executeUpdate();
        }
    }

    private void insertNode(Connection connection, FireflyNode node) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_node
                (node_id, roles, registered_at, last_heartbeat_at, status, metadata)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, node.nodeId());
            statement.setString(2, JdbcEncoding.encodeRoles(node.roles()));
            statement.setTimestamp(3, Timestamp.from(node.registeredAt()));
            statement.setTimestamp(4, Timestamp.from(node.lastHeartbeatAt()));
            statement.setString(5, node.status().name());
            statement.setString(6, JdbcEncoding.encodeMap(node.metadata()));
            statement.executeUpdate();
        }
    }

    private void bindNodeForUpdate(PreparedStatement statement, FireflyNode node) throws SQLException {
        statement.setString(1, JdbcEncoding.encodeRoles(node.roles()));
        statement.setTimestamp(2, Timestamp.from(node.registeredAt()));
        statement.setTimestamp(3, Timestamp.from(node.lastHeartbeatAt()));
        statement.setString(4, node.status().name());
        statement.setString(5, JdbcEncoding.encodeMap(node.metadata()));
        statement.setString(6, node.nodeId());
    }

    private FireflyNode mapNode(ResultSet resultSet) throws SQLException {
        return new FireflyNode(
                resultSet.getString("node_id"),
                JdbcEncoding.decodeRoles(resultSet.getString("roles")),
                resultSet.getTimestamp("registered_at").toInstant(),
                resultSet.getTimestamp("last_heartbeat_at").toInstant(),
                NodeStatus.valueOf(resultSet.getString("status")),
                JdbcEncoding.decodeMap(resultSet.getString("metadata"))
        );
    }
}
