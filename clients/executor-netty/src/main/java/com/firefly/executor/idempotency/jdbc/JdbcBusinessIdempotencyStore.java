package com.firefly.executor.idempotency.jdbc;

import com.firefly.idempotency.FencedBusinessIdempotencyStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** JDBC business-side idempotency store with row locking, claim expiry and fencing tokens. */
public final class JdbcBusinessIdempotencyStore implements FencedBusinessIdempotencyStore {
    public static final String DEFAULT_TABLE = "firefly_executor_idempotency";

    private final DataSource dataSource;
    private final Duration abandonedClaimTimeout;
    private final String tableName;

    public JdbcBusinessIdempotencyStore(DataSource dataSource, Duration abandonedClaimTimeout) {
        this(dataSource, abandonedClaimTimeout, DEFAULT_TABLE);
    }

    public JdbcBusinessIdempotencyStore(
            DataSource dataSource, Duration abandonedClaimTimeout, String tableName
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.abandonedClaimTimeout = Objects.requireNonNull(abandonedClaimTimeout, "abandonedClaimTimeout");
        if (abandonedClaimTimeout.isZero() || abandonedClaimTimeout.isNegative()) {
            throw new IllegalArgumentException("abandonedClaimTimeout must be positive");
        }
        if (tableName == null || !tableName.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid idempotency table name");
        }
        this.tableName = tableName;
    }

    @Override
    public Claim tryAcquireFenced(String key, Instant acquiredAt) {
        requireKey(key);
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Instant now = databaseNow(connection);
                    Existing existing = selectForUpdate(connection, key);
                    if (existing == null) {
                        String token = UUID.randomUUID().toString();
                        try {
                            insert(connection, key, token, now);
                            connection.commit();
                            return Claim.acquired(token);
                        } catch (SQLException concurrentInsert) {
                            connection.rollback();
                            if (attempt == 0) continue;
                            throw concurrentInsert;
                        }
                    }
                    if ("COMPLETED".equals(existing.status())) {
                        connection.commit();
                        return Claim.completed();
                    }
                    if ("IN_PROGRESS".equals(existing.status()) && existing.claimUntil().isAfter(now)) {
                        connection.commit();
                        return Claim.inProgress();
                    }
                    String token = UUID.randomUUID().toString();
                    reclaim(connection, key, token, now);
                    connection.commit();
                    return Claim.acquired(token);
                } catch (SQLException | RuntimeException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("failed to acquire business idempotency claim", e);
            }
        }
        throw new IllegalStateException("failed to acquire business idempotency claim");
    }

    @Override
    public boolean markCompletedFenced(String key, String claimToken, Instant completedAt) {
        requireKey(key);
        requireToken(claimToken);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update %s set status='COMPLETED', completed_at=?, claim_until=?,
                         error_message='', updated_at=?
                     where idempotency_key=? and status='IN_PROGRESS' and claim_token=?
                     """.formatted(tableName))) {
            Instant now = databaseNow(connection);
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, key);
            statement.setString(5, claimToken);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to complete business idempotency claim", e);
        }
    }

    @Override
    public boolean releaseFenced(String key, String claimToken, Instant releasedAt, String errorMessage) {
        requireKey(key);
        requireToken(claimToken);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update %s set status='FAILED', claim_until=?, error_message=?, updated_at=?
                     where idempotency_key=? and status='IN_PROGRESS' and claim_token=?
                     """.formatted(tableName))) {
            Instant now = databaseNow(connection);
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, truncate(errorMessage));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, key);
            statement.setString(5, claimToken);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to release business idempotency claim", e);
        }
    }

    public int deleteTerminalBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1) return 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                java.util.List<String> keys = new java.util.ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement("""
                        select idempotency_key from %s
                        where status in ('COMPLETED','FAILED') and updated_at<?
                        order by updated_at, idempotency_key
                        """.formatted(tableName))) {
                    select.setTimestamp(1, Timestamp.from(cutoff));
                    select.setMaxRows(limit);
                    try (ResultSet resultSet = select.executeQuery()) {
                        while (resultSet.next()) keys.add(resultSet.getString(1));
                    }
                }
                int deleted = 0;
                try (PreparedStatement delete = connection.prepareStatement("""
                        delete from %s where idempotency_key=?
                        and status in ('COMPLETED','FAILED') and updated_at<?
                        """.formatted(tableName))) {
                    for (String key : keys) {
                        delete.setString(1, key);
                        delete.setTimestamp(2, Timestamp.from(cutoff));
                        deleted += delete.executeUpdate();
                    }
                }
                connection.commit();
                return deleted;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to clean business idempotency records", e);
        }
    }

    private Existing selectForUpdate(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select status, claim_token, claim_until from %s
                where idempotency_key=? for update
                """.formatted(tableName))) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                return new Existing(
                        resultSet.getString("status"), resultSet.getString("claim_token"),
                        resultSet.getTimestamp("claim_until").toInstant()
                );
            }
        }
    }

    private void insert(Connection connection, String key, String token, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into %s
                (idempotency_key, status, claim_token, claim_until, attempt, error_message,
                 created_at, updated_at, completed_at)
                values (?, 'IN_PROGRESS', ?, ?, 1, '', ?, ?, null)
                """.formatted(tableName))) {
            statement.setString(1, key);
            statement.setString(2, token);
            statement.setTimestamp(3, Timestamp.from(now.plus(abandonedClaimTimeout)));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void reclaim(Connection connection, String key, String token, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update %s set status='IN_PROGRESS', claim_token=?, claim_until=?, attempt=attempt+1,
                    error_message='', updated_at=?, completed_at=null
                where idempotency_key=?
                """.formatted(tableName))) {
            statement.setString(1, token);
            statement.setTimestamp(2, Timestamp.from(now.plus(abandonedClaimTimeout)));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, key);
            statement.executeUpdate();
        }
    }

    private Instant databaseNow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select current_timestamp");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) throw new SQLException("database did not return current_timestamp");
            return resultSet.getTimestamp(1).toInstant();
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key must not be blank");
    }

    private void requireToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("claim token must not be blank");
    }

    private String truncate(String errorMessage) {
        if (errorMessage == null) return "";
        return errorMessage.length() <= 4000 ? errorMessage : errorMessage.substring(0, 4000);
    }

    private record Existing(String status, String claimToken, Instant claimUntil) {
    }
}
