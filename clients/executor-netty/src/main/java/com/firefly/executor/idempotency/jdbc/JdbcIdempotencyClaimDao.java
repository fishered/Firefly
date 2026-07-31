package com.firefly.executor.idempotency.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** SQL mapping for executor business-idempotency claim rows. */
final class JdbcIdempotencyClaimDao {
    private final String tableName;

    JdbcIdempotencyClaimDao(String tableName) {
        this.tableName = tableName;
    }

    ClaimRow findForUpdate(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select status, claim_token, claim_until, attempt from %s
                where idempotency_key=? for update
                """.formatted(tableName))) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return null;
                return new ClaimRow(
                        ClaimStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("claim_token"),
                        resultSet.getTimestamp("claim_until").toInstant(),
                        resultSet.getLong("attempt")
                );
            }
        }
    }

    void insert(
            Connection connection,
            String key,
            long generation,
            Instant now,
            Instant claimUntil
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into %s
                (idempotency_key, status, claim_token, claim_until, attempt, error_message,
                 created_at, updated_at, completed_at)
                values (?, 'IN_PROGRESS', ?, ?, ?, '', ?, ?, null)
                """.formatted(tableName))) {
            statement.setString(1, key);
            statement.setString(2, Long.toString(generation));
            statement.setTimestamp(3, Timestamp.from(claimUntil));
            statement.setLong(4, generation);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    boolean reclaim(
            Connection connection,
            String key,
            String expectedToken,
            long generation,
            Instant now,
            Instant claimUntil
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update %s set status='IN_PROGRESS', claim_token=?, claim_until=?, attempt=?,
                    error_message='', updated_at=?, completed_at=null
                where idempotency_key=? and claim_token=?
                """.formatted(tableName))) {
            statement.setString(1, Long.toString(generation));
            statement.setTimestamp(2, Timestamp.from(claimUntil));
            statement.setLong(3, generation);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, key);
            statement.setString(6, expectedToken);
            return statement.executeUpdate() == 1;
        }
    }

    boolean complete(Connection connection, String key, String claimToken, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update %s set status='COMPLETED', completed_at=?, claim_until=?,
                    error_message='', updated_at=?
                where idempotency_key=? and status='IN_PROGRESS' and claim_token=?
                """.formatted(tableName))) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, key);
            statement.setString(5, claimToken);
            return statement.executeUpdate() == 1;
        }
    }

    boolean release(
            Connection connection,
            String key,
            String claimToken,
            Instant now,
            String errorMessage
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update %s set status='FAILED', claim_until=?, error_message=?, updated_at=?
                where idempotency_key=? and status='IN_PROGRESS' and claim_token=?
                """.formatted(tableName))) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, errorMessage);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, key);
            statement.setString(5, claimToken);
            return statement.executeUpdate() == 1;
        }
    }

    List<String> findTerminalKeysBefore(
            Connection connection, Instant cutoff, int limit
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select idempotency_key from %s
                where status in ('COMPLETED','FAILED') and updated_at<?
                order by updated_at, idempotency_key
                """.formatted(tableName))) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            statement.setMaxRows(limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (resultSet.next()) keys.add(resultSet.getString(1));
                return keys;
            }
        }
    }

    int deleteTerminalKeysBefore(
            Connection connection, List<String> keys, Instant cutoff
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                delete from %s where idempotency_key=?
                and status in ('COMPLETED','FAILED') and updated_at<?
                """.formatted(tableName))) {
            int deleted = 0;
            for (String key : keys) {
                statement.setString(1, key);
                statement.setTimestamp(2, Timestamp.from(cutoff));
                deleted += statement.executeUpdate();
            }
            return deleted;
        }
    }

    Instant databaseNow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select current_timestamp");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) throw new SQLException("database did not return current_timestamp");
            return resultSet.getTimestamp(1).toInstant();
        }
    }

    enum ClaimStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    record ClaimRow(ClaimStatus status, String claimToken, Instant claimUntil, long generation) {
    }
}
