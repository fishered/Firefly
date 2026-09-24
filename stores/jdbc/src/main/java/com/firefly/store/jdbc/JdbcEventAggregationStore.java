package com.firefly.store.jdbc;

import com.firefly.trigger.AggregatedEvent;
import com.firefly.trigger.EventAggregationPolicy;
import com.firefly.trigger.EventAggregationStore;
import com.firefly.trigger.EventTrigger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC-backed event windows with row-locked aggregation and leased release claims. */
public final class JdbcEventAggregationStore implements EventAggregationStore {
    private final DataSource dataSource;

    public JdbcEventAggregationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<AggregatedEvent> add(
            String jobId,
            EventTrigger trigger,
            EventAggregationPolicy policy,
            Instant now,
            String claimantId,
            Duration claimLease
    ) {
        validate(jobId, trigger, policy, now, claimantId, claimLease);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureGroupLock(connection, jobId, policy.aggregationKey());
                lockGroup(connection, jobId, policy.aggregationKey());
                if (containsEvent(connection, trigger.idempotencyKey())) {
                    connection.commit();
                    return Optional.empty();
                }
                AggregateRow row = findPending(connection, jobId, policy.aggregationKey())
                        .orElse(null);
                if (row == null) {
                    row = new AggregateRow(UUID.randomUUID().toString(), jobId, policy.aggregationKey(),
                            trigger.payload(), 1, now, now.plus(policy.debounceWindow()),
                            now.plus(policy.maxDelay()));
                    insertAggregate(connection, row, now);
                } else {
                    row = new AggregateRow(row.aggregateId, row.jobId, row.aggregationKey,
                            trigger.payload(), row.eventCount + 1, row.firstReceivedAt,
                            now.plus(policy.debounceWindow()), row.deadlineAt);
                    updateAggregate(connection, row, now);
                }
                insertMember(connection, row.aggregateId, trigger.idempotencyKey());
                boolean due = !now.isBefore(row.readyAt) || !now.isBefore(row.deadlineAt);
                Optional<AggregatedEvent> result = Optional.empty();
                if (due) {
                    claim(connection, row.aggregateId, claimantId, now.plus(claimLease), now);
                    result = Optional.of(loadEvent(connection, row));
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to aggregate event", failure);
        }
    }

    @Override
    public List<AggregatedEvent> claimDue(
            Instant now, String claimantId, Duration claimLease, int limit
    ) {
        Objects.requireNonNull(now, "now");
        validateClaim(claimantId, claimLease);
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    select aggregate_id, job_id, aggregation_key, latest_payload, event_count,
                           first_received_at, ready_at, deadline_at
                    from firefly_event_aggregate
                    where (status='PENDING' and (ready_at<=? or deadline_at<=?))
                       or (status='CLAIMED' and claim_until<=?)
                    order by ready_at, aggregate_id
                    for update
                    """)) {
                Timestamp timestamp = Timestamp.from(now);
                statement.setTimestamp(1, timestamp);
                statement.setTimestamp(2, timestamp);
                statement.setTimestamp(3, timestamp);
                statement.setMaxRows(limit);
                List<AggregateRow> rows = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) rows.add(readRow(resultSet));
                }
                List<AggregatedEvent> result = new ArrayList<>();
                for (AggregateRow row : rows) {
                    claim(connection, row.aggregateId, claimantId, now.plus(claimLease), now);
                    result.add(loadEvent(connection, row));
                }
                connection.commit();
                return List.copyOf(result);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to claim event aggregates", failure);
        }
    }

    @Override
    public boolean complete(String aggregateId, String claimantId) {
        validateIdentity(aggregateId, claimantId);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement aggregate = connection.prepareStatement("""
                    delete from firefly_event_aggregate
                    where aggregate_id=? and status='CLAIMED' and claim_owner=?
                    """);
                 PreparedStatement members = connection.prepareStatement("""
                    delete from firefly_event_aggregate_member where aggregate_id=?
                    """)) {
                aggregate.setString(1, aggregateId);
                aggregate.setString(2, claimantId);
                boolean deleted = aggregate.executeUpdate() == 1;
                if (deleted) {
                    members.setString(1, aggregateId);
                    members.executeUpdate();
                }
                connection.commit();
                return deleted;
            } catch (SQLException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to complete event aggregate", failure);
        }
    }

    @Override
    public boolean release(String aggregateId, String claimantId, Instant now) {
        validateIdentity(aggregateId, claimantId);
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_event_aggregate set claim_until=?, updated_at=?
                     where aggregate_id=? and status='CLAIMED' and claim_owner=?
                     """)) {
            Timestamp timestamp = Timestamp.from(now);
            statement.setTimestamp(1, timestamp);
            statement.setTimestamp(2, timestamp);
            statement.setString(3, aggregateId);
            statement.setString(4, claimantId);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new JdbcException("failed to release event aggregate", failure);
        }
    }

    private void ensureGroupLock(Connection connection, String jobId, String aggregationKey) throws SQLException {
        JdbcDialect dialect = JdbcDialect.resolve(connection, JdbcSchemaOptions.auto());
        String sql = switch (dialect) {
            case H2 -> "merge into firefly_event_aggregation_lock (job_id, aggregation_key) key(job_id, aggregation_key) values (?, ?)";
            case MYSQL -> "insert into firefly_event_aggregation_lock (job_id, aggregation_key) values (?, ?) on duplicate key update job_id=values(job_id)";
            case POSTGRESQL -> "insert into firefly_event_aggregation_lock (job_id, aggregation_key) values (?, ?) on conflict do nothing";
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, jobId);
            statement.setString(2, aggregationKey);
            statement.executeUpdate();
        }
    }

    private void lockGroup(Connection connection, String jobId, String aggregationKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select job_id from firefly_event_aggregation_lock
                where job_id=? and aggregation_key=? for update
                """)) {
            statement.setString(1, jobId);
            statement.setString(2, aggregationKey);
            try (ResultSet ignored = statement.executeQuery()) {
                if (!ignored.next()) throw new SQLException("event aggregation lock row is missing");
            }
        }
    }

    private boolean containsEvent(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select aggregate_id from firefly_event_aggregate_member where idempotency_key=?
                """)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private Optional<AggregateRow> findPending(
            Connection connection, String jobId, String aggregationKey
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select aggregate_id, job_id, aggregation_key, latest_payload, event_count,
                       first_received_at, ready_at, deadline_at
                from firefly_event_aggregate
                where job_id=? and aggregation_key=? and status='PENDING'
                order by first_received_at desc
                for update
                """)) {
            statement.setString(1, jobId);
            statement.setString(2, aggregationKey);
            statement.setMaxRows(1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readRow(resultSet)) : Optional.empty();
            }
        }
    }

    private void insertAggregate(Connection connection, AggregateRow row, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_event_aggregate
                (aggregate_id, job_id, aggregation_key, latest_payload, event_count, first_received_at,
                 ready_at, deadline_at, status, claim_owner, claim_until, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', null, null, ?)
                """)) {
            bindRow(statement, row);
            statement.setTimestamp(9, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void updateAggregate(Connection connection, AggregateRow row, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_event_aggregate
                set latest_payload=?, event_count=?, ready_at=?, updated_at=?
                where aggregate_id=? and status='PENDING'
                """)) {
            statement.setString(1, row.latestPayload);
            statement.setInt(2, row.eventCount);
            statement.setTimestamp(3, Timestamp.from(row.readyAt));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setString(5, row.aggregateId);
            if (statement.executeUpdate() != 1) throw new SQLException("event aggregate changed while locked");
        }
    }

    private void bindRow(PreparedStatement statement, AggregateRow row) throws SQLException {
        statement.setString(1, row.aggregateId);
        statement.setString(2, row.jobId);
        statement.setString(3, row.aggregationKey);
        statement.setString(4, row.latestPayload);
        statement.setInt(5, row.eventCount);
        statement.setTimestamp(6, Timestamp.from(row.firstReceivedAt));
        statement.setTimestamp(7, Timestamp.from(row.readyAt));
        statement.setTimestamp(8, Timestamp.from(row.deadlineAt));
    }

    private void insertMember(Connection connection, String aggregateId, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_event_aggregate_member (idempotency_key, aggregate_id) values (?, ?)
                """)) {
            statement.setString(1, idempotencyKey);
            statement.setString(2, aggregateId);
            statement.executeUpdate();
        }
    }

    private void claim(
            Connection connection, String aggregateId, String claimantId, Instant claimUntil, Instant now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_event_aggregate
                set status='CLAIMED', claim_owner=?, claim_until=?, updated_at=?
                where aggregate_id=?
                """)) {
            statement.setString(1, claimantId);
            statement.setTimestamp(2, Timestamp.from(claimUntil));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setString(4, aggregateId);
            if (statement.executeUpdate() != 1) throw new SQLException("event aggregate claim failed");
        }
    }

    private AggregatedEvent loadEvent(Connection connection, AggregateRow row) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select idempotency_key from firefly_event_aggregate_member
                where aggregate_id=? order by idempotency_key
                """)) {
            statement.setString(1, row.aggregateId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) keys.add(resultSet.getString(1));
            }
        }
        return new AggregatedEvent(row.aggregateId, row.jobId, row.aggregationKey, row.latestPayload,
                row.eventCount, row.firstReceivedAt, row.readyAt, keys);
    }

    private AggregateRow readRow(ResultSet resultSet) throws SQLException {
        return new AggregateRow(resultSet.getString("aggregate_id"), resultSet.getString("job_id"),
                resultSet.getString("aggregation_key"), resultSet.getString("latest_payload"),
                resultSet.getInt("event_count"), resultSet.getTimestamp("first_received_at").toInstant(),
                resultSet.getTimestamp("ready_at").toInstant(),
                resultSet.getTimestamp("deadline_at").toInstant());
    }

    private static void validate(
            String jobId, EventTrigger trigger, EventAggregationPolicy policy, Instant now,
            String claimantId, Duration claimLease
    ) {
        if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("jobId must not be blank");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        validateClaim(claimantId, claimLease);
    }

    private static void validateClaim(String claimantId, Duration claimLease) {
        if (claimantId == null || claimantId.isBlank() || claimantId.length() > 128) {
            throw new IllegalArgumentException("claimantId must be between 1 and 128 characters");
        }
        Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    private static void validateIdentity(String aggregateId, String claimantId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId must not be blank");
        }
        validateClaim(claimantId, Duration.ofSeconds(1));
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private record AggregateRow(
            String aggregateId,
            String jobId,
            String aggregationKey,
            String latestPayload,
            int eventCount,
            Instant firstReceivedAt,
            Instant readyAt,
            Instant deadlineAt
    ) { }
}
