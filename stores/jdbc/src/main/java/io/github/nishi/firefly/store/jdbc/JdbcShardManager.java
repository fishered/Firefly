package io.github.nishi.firefly.store.jdbc;

import io.github.nishi.firefly.cluster.ShardLease;
import io.github.nishi.firefly.cluster.ShardManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC shard lease manager using database transactions as the coordination boundary.
 */
public final class JdbcShardManager implements ShardManager {
    private static final Instant RELEASED_LEASE_UNTIL = Instant.EPOCH;

    private final DataSource dataSource;

    public JdbcShardManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<ShardLease> acquire(int shardId, String nodeId, Instant now, Duration leaseDuration) {
        validate(shardId, nodeId, now, leaseDuration);
        try (Connection connection = dataSource.getConnection()) {
            /**
             * The lease row is the coordination boundary. Locking it in a transaction prevents two
             * scheduler nodes from both deciding that an expired shard belongs to them.
             */
            connection.setAutoCommit(false);
            try {
                ShardLease current = selectForUpdate(connection, shardId).orElse(null);
                ShardLease next;
                if (current == null) {
                    next = new ShardLease(shardId, nodeId, now.plus(leaseDuration), 1L);
                    insert(connection, next);
                } else {
                    boolean heldByOther = current.leaseUntil().isAfter(now) && !current.ownerNodeId().equals(nodeId);
                    if (heldByOther) {
                        connection.rollback();
                        return Optional.empty();
                    }
                    /**
                     * Reacquiring by the same owner is a renewal-style operation. A different owner
                     * must receive a larger fencing token so stale commands can be rejected later.
                     */
                    long token = current.ownerNodeId().equals(nodeId)
                            ? current.fencingToken()
                            : current.fencingToken() + 1;
                    next = new ShardLease(shardId, nodeId, now.plus(leaseDuration), token);
                    update(connection, next);
                }
                connection.commit();
                return Optional.of(next);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to acquire shard lease", e);
        }
    }

    @Override
    public Optional<ShardLease> renew(
            int shardId,
            String nodeId,
            long fencingToken,
            Instant now,
            Duration leaseDuration
    ) {
        validate(shardId, nodeId, now, leaseDuration);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_shard_lease
                     set lease_until = ?
                     where shard_id = ?
                       and owner_node_id = ?
                       and fencing_token = ?
                       and lease_until >= ?
                     """)) {
            Instant leaseUntil = now.plus(leaseDuration);
            statement.setTimestamp(1, Timestamp.from(leaseUntil));
            statement.setInt(2, shardId);
            statement.setString(3, nodeId);
            statement.setLong(4, fencingToken);
            statement.setTimestamp(5, Timestamp.from(now));
            if (statement.executeUpdate() == 0) {
                return Optional.empty();
            }
            return Optional.of(new ShardLease(shardId, nodeId, leaseUntil, fencingToken));
        } catch (SQLException e) {
            throw new JdbcException("failed to renew shard lease", e);
        }
    }

    @Override
    public boolean release(int shardId, String nodeId, long fencingToken) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_shard_lease
                     set lease_until = ?
                     where shard_id = ?
                       and owner_node_id = ?
                       and fencing_token = ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(RELEASED_LEASE_UNTIL));
            statement.setInt(2, shardId);
            statement.setString(3, nodeId);
            statement.setLong(4, fencingToken);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to release shard lease", e);
        }
    }

    @Override
    public Optional<ShardLease> findLease(int shardId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select shard_id, owner_node_id, lease_until, fencing_token
                     from firefly_shard_lease
                     where shard_id = ?
                     """)) {
            statement.setInt(1, shardId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapLease(resultSet));
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find shard lease", e);
        }
    }

    private Optional<ShardLease> selectForUpdate(Connection connection, int shardId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select shard_id, owner_node_id, lease_until, fencing_token
                from firefly_shard_lease
                where shard_id = ?
                for update
                """)) {
            statement.setInt(1, shardId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapLease(resultSet));
            }
        }
    }

    private void insert(Connection connection, ShardLease lease) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_shard_lease
                (shard_id, owner_node_id, lease_until, fencing_token)
                values (?, ?, ?, ?)
                """)) {
            statement.setInt(1, lease.shardId());
            statement.setString(2, lease.ownerNodeId());
            statement.setTimestamp(3, Timestamp.from(lease.leaseUntil()));
            statement.setLong(4, lease.fencingToken());
            statement.executeUpdate();
        }
    }

    private void update(Connection connection, ShardLease lease) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_shard_lease
                set owner_node_id = ?, lease_until = ?, fencing_token = ?
                where shard_id = ?
                """)) {
            statement.setString(1, lease.ownerNodeId());
            statement.setTimestamp(2, Timestamp.from(lease.leaseUntil()));
            statement.setLong(3, lease.fencingToken());
            statement.setInt(4, lease.shardId());
            statement.executeUpdate();
        }
    }

    private ShardLease mapLease(ResultSet resultSet) throws SQLException {
        return new ShardLease(
                resultSet.getInt("shard_id"),
                resultSet.getString("owner_node_id"),
                resultSet.getTimestamp("lease_until").toInstant(),
                resultSet.getLong("fencing_token")
        );
    }

    private void validate(int shardId, String nodeId, Instant now, Duration leaseDuration) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must not be negative");
        }
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }
}
