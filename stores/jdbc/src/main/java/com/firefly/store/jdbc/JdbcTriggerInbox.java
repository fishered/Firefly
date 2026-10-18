package com.firefly.store.jdbc;

import com.firefly.trigger.EventTrigger;
import com.firefly.trigger.FailedEventTrigger;
import com.firefly.trigger.TriggerInbox;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC-backed idempotency, failure recovery, and signed-request replay boundary. */
public final class JdbcTriggerInbox implements TriggerInbox {
    private final DataSource dataSource;

    public JdbcTriggerInbox(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean receive(EventTrigger event) {
        return receive("", event);
    }

    @Override
    public boolean receive(String jobId, EventTrigger event) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     insert into firefly_trigger_inbox
                     (event_id, job_id, event_type, idempotency_key, payload, status,
                      received_at, processed_at, failure_reason)
                     values(?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, event.eventId());
            statement.setString(2, jobId == null ? "" : jobId);
            statement.setString(3, event.eventType());
            statement.setString(4, event.idempotencyKey());
            statement.setString(5, event.payload());
            statement.setString(6, event.status().name());
            statement.setTimestamp(7, Timestamp.from(event.receivedAt()));
            setInstant(statement, 8, event.processedAt());
            statement.setString(9, "");
            statement.executeUpdate();
            return true;
        } catch (SQLException failure) {
            if (isConstraint(failure)) return false;
            throw new JdbcException("failed to receive trigger", failure);
        }
    }

    @Override
    public Optional<EventTrigger> findByIdempotencyKey(String key) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select event_id, job_id, event_type, idempotency_key, payload, status,
                            received_at, processed_at, failure_reason
                     from firefly_trigger_inbox where idempotency_key=?
                     """)) {
            statement.setString(1, key);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to read trigger", failure);
        }
    }

    @Override
    public boolean markProcessed(String key, Instant at) {
        return transition(key, EventTrigger.TriggerStatus.RECEIVED,
                EventTrigger.TriggerStatus.PROCESSED, at, "");
    }

    @Override
    public boolean markFailed(String key, Instant at) {
        return markFailed(key, at, "");
    }

    @Override
    public boolean markFailed(String key, Instant at, String reason) {
        return transition(key, EventTrigger.TriggerStatus.RECEIVED,
                EventTrigger.TriggerStatus.FAILED, at, reason == null ? "" : reason);
    }

    @Override
    public boolean reserveNonce(String nonce, Instant expiresAt, Instant now) {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "delete from firefly_trigger_nonce where expires_at <= ?")) {
                    cleanup.setTimestamp(1, Timestamp.from(now));
                    cleanup.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        insert into firefly_trigger_nonce(nonce, expires_at, created_at) values(?,?,?)
                        """)) {
                    insert.setString(1, nonce);
                    insert.setTimestamp(2, Timestamp.from(expiresAt));
                    insert.setTimestamp(3, Timestamp.from(now));
                    insert.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException failure) {
                connection.rollback();
                if (isConstraint(failure)) return false;
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to reserve trigger nonce", failure);
        }
    }

    @Override
    public List<FailedEventTrigger> listFailed(int limit) {
        if (limit <= 0) return List.of();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select event_id, job_id, event_type, idempotency_key, payload, status,
                            received_at, processed_at, failure_reason
                     from firefly_trigger_inbox
                     where status='FAILED'
                     order by received_at desc, idempotency_key desc
                     """)) {
            statement.setMaxRows(limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FailedEventTrigger> events = new ArrayList<>();
                while (resultSet.next()) events.add(mapFailed(resultSet));
                return List.copyOf(events);
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to list failed triggers", failure);
        }
    }

    @Override
    public Optional<FailedEventTrigger> claimFailedForReplay(String key, Instant claimedAt) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement("""
                        update firefly_trigger_inbox
                        set status='RECEIVED', processed_at=null, failure_reason=''
                        where idempotency_key=? and status='FAILED'
                        """)) {
                    statement.setString(1, key);
                    updated = statement.executeUpdate();
                }
                Optional<FailedEventTrigger> result = updated == 1 ? findFailed(connection, key) : Optional.empty();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to claim trigger replay", failure);
        }
    }

    @Override
    public boolean acknowledgeFailed(String key, Instant acknowledgedAt) {
        return transition(key, EventTrigger.TriggerStatus.FAILED,
                EventTrigger.TriggerStatus.ACKNOWLEDGED, acknowledgedAt, null);
    }

    @Override
    public long countFailed() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "select count(*) from firefly_trigger_inbox where status='FAILED'");
             var resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        } catch (SQLException failure) {
            throw new JdbcException("failed to count failed triggers", failure);
        }
    }

    private boolean transition(String key, EventTrigger.TriggerStatus expected,
                               EventTrigger.TriggerStatus target, Instant at, String reason) {
        String sql = reason == null
                ? "update firefly_trigger_inbox set status=?,processed_at=? where idempotency_key=? and status=?"
                : "update firefly_trigger_inbox set status=?,processed_at=?,failure_reason=? where idempotency_key=? and status=?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.name());
            statement.setTimestamp(2, Timestamp.from(at));
            int offset = 0;
            if (reason != null) {
                statement.setString(3, reason);
                offset = 1;
            }
            statement.setString(3 + offset, key);
            statement.setString(4 + offset, expected.name());
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new JdbcException("failed to update trigger", failure);
        }
    }

    private Optional<EventTrigger> find(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select event_id, job_id, event_type, idempotency_key, payload, status,
                       received_at, processed_at, failure_reason
                from firefly_trigger_inbox where idempotency_key=?
                """)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private EventTrigger map(ResultSet resultSet) throws SQLException {
        Timestamp processedAt = resultSet.getTimestamp("processed_at");
        return new EventTrigger(
                resultSet.getString("event_id"), resultSet.getString("event_type"), resultSet.getString("idempotency_key"),
                resultSet.getString("payload"), resultSet.getTimestamp("received_at").toInstant(),
                EventTrigger.TriggerStatus.valueOf(resultSet.getString("status")),
                processedAt == null ? null : processedAt.toInstant()
        );
    }

    private Optional<FailedEventTrigger> findFailed(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select event_id, job_id, event_type, idempotency_key, payload, status,
                       received_at, processed_at, failure_reason
                from firefly_trigger_inbox where idempotency_key=?
                """)) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapFailed(resultSet)) : Optional.empty();
            }
        }
    }

    private FailedEventTrigger mapFailed(ResultSet resultSet) throws SQLException {
        return new FailedEventTrigger(map(resultSet), resultSet.getString("job_id"),
                resultSet.getString("failure_reason"));
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setTimestamp(index, null);
        else statement.setTimestamp(index, Timestamp.from(value));
    }

    private boolean isConstraint(SQLException failure) {
        String state = failure.getSQLState();
        return "23505".equals(state) || "23000".equals(state);
    }
}
