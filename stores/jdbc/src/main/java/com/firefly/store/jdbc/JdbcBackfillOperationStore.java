package com.firefly.store.jdbc;

import com.firefly.trigger.BackfillItem;
import com.firefly.trigger.BackfillOperation;
import com.firefly.trigger.BackfillOperationStore;
import com.firefly.trigger.BackfillOptions;
import com.firefly.trigger.BackfillProgress;
import com.firefly.trigger.BackfillRequest;

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
import java.util.Set;

/** JDBC backfill cursor store with operation leases and optimistic version fencing. */
public final class JdbcBackfillOperationStore implements BackfillOperationStore {
    private final DataSource dataSource;

    public JdbcBackfillOperationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean create(BackfillOperation operation, List<BackfillItem> items) {
        Objects.requireNonNull(operation, "operation");
        items = List.copyOf(items);
        if (operation.expanded() != items.size()) {
            throw new IllegalArgumentException("backfill item count does not match expanded count");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertOperation(connection, operation);
                insertItems(connection, operation.request().requestId(), items, operation.createdAt());
                connection.commit();
                return true;
            } catch (SQLException failure) {
                rollback(connection, failure);
                if (constraintViolation(failure)) return false;
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to create backfill operation", failure);
        }
    }

    @Override
    public Optional<BackfillOperation> find(String requestId) {
        try (Connection connection = dataSource.getConnection()) {
            return find(connection, requestId, false);
        } catch (SQLException failure) {
            throw new JdbcException("failed to find backfill operation", failure);
        }
    }

    @Override
    public List<BackfillOperation> list(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from firefly_backfill_operation
                     order by created_at desc, request_id
                     """)) {
            statement.setMaxRows(limit);
            List<BackfillOperation> operations = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) operations.add(map(resultSet));
            }
            return List.copyOf(operations);
        } catch (SQLException failure) {
            throw new JdbcException("failed to list backfill operations", failure);
        }
    }

    @Override
    public Optional<BackfillOperation> claim(
            String requestId, Instant now, String claimantId, Duration claimLease
    ) {
        validateClaim(now, claimantId, claimLease);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BackfillOperation current = find(connection, requestId, true).orElse(null);
                if (current == null || !runnable(current, now) || !claimAvailable(current, now)) {
                    connection.commit();
                    return Optional.empty();
                }
                BackfillOperation claimed = claim(connection, current, now, claimantId, claimLease);
                connection.commit();
                return Optional.of(claimed);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to claim backfill operation", failure);
        }
    }

    @Override
    public List<BackfillOperation> claimRunnable(
            Instant now, String claimantId, Duration claimLease, int limit
    ) {
        validateClaim(now, claimantId, claimLease);
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    select * from firefly_backfill_operation
                    where status in ('PENDING','RUNNING') and next_allowed_at<=?
                      and (claim_until is null or claim_until<=?)
                    order by created_at, request_id
                    for update
                    """)) {
                Timestamp timestamp = Timestamp.from(now);
                statement.setTimestamp(1, timestamp);
                statement.setTimestamp(2, timestamp);
                statement.setMaxRows(limit);
                List<BackfillOperation> candidates = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) candidates.add(map(resultSet));
                }
                List<BackfillOperation> claimed = new ArrayList<>(candidates.size());
                for (BackfillOperation candidate : candidates) {
                    claimed.add(claim(connection, candidate, now, claimantId, claimLease));
                }
                connection.commit();
                return List.copyOf(claimed);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to claim runnable backfill operations", failure);
        }
    }

    @Override
    public Optional<BackfillItem> nextItem(String requestId, String claimantId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select item.ordinal, item.fire_time, item.execution_id, item.status, item.error_message
                     from firefly_backfill_operation operation
                     join firefly_backfill_item item
                       on item.request_id=operation.request_id and item.ordinal=operation.cursor_position
                     where operation.request_id=? and operation.claim_owner=?
                     """)) {
            statement.setString(1, requestId);
            statement.setString(2, claimantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Optional.empty();
                return Optional.of(new BackfillItem(
                        resultSet.getInt("ordinal"), resultSet.getTimestamp("fire_time").toInstant(),
                        resultSet.getString("execution_id"),
                        BackfillItem.BackfillItemStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("error_message")
                ));
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to read next backfill item", failure);
        }
    }

    @Override
    public Optional<BackfillOperation> recordItem(
            String requestId,
            String claimantId,
            long expectedVersion,
            BackfillItem.BackfillItemStatus itemStatus,
            String error,
            Instant nextAllowedAt,
            Instant now
    ) {
        Objects.requireNonNull(itemStatus, "itemStatus");
        Objects.requireNonNull(nextAllowedAt, "nextAllowedAt");
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BackfillOperation current = find(connection, requestId, true).orElse(null);
                if (current == null || !current.claimedBy(claimantId)
                        || current.version() != expectedVersion || current.cursor() >= current.expanded()) {
                    connection.commit();
                    return Optional.empty();
                }
                try (PreparedStatement item = connection.prepareStatement("""
                        update firefly_backfill_item set status=?, error_message=?, updated_at=?
                        where request_id=? and ordinal=? and status='PENDING'
                        """)) {
                    item.setString(1, itemStatus.name());
                    item.setString(2, error == null ? "" : error);
                    item.setTimestamp(3, Timestamp.from(now));
                    item.setString(4, requestId);
                    item.setInt(5, current.cursor());
                    if (item.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                int cursor = current.cursor() + 1;
                int dispatched = current.dispatched()
                        + (itemStatus == BackfillItem.BackfillItemStatus.DISPATCHED ? 1 : 0);
                int failed = current.failed()
                        + (itemStatus == BackfillItem.BackfillItemStatus.FAILED ? 1 : 0);
                BackfillProgress.BackfillStatus status = BackfillProgress.BackfillStatus.RUNNING;
                String owner = claimantId;
                Instant claimUntil = current.claimUntil();
                if (cursor >= current.expanded()) {
                    status = BackfillProgress.BackfillStatus.COMPLETED;
                    owner = "";
                    claimUntil = null;
                } else if (current.canaryActive() && cursor >= current.canaryExecutions()) {
                    status = BackfillProgress.BackfillStatus.PAUSED;
                    owner = "";
                    claimUntil = null;
                }
                BackfillOperation updated = copy(current, status, dispatched, failed, cursor,
                        current.canaryActive(), nextAllowedAt, owner, claimUntil,
                        current.version() + 1, now);
                updateOperation(connection, updated, expectedVersion, claimantId);
                connection.commit();
                return Optional.of(updated);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to record backfill item", failure);
        }
    }

    @Override
    public boolean release(String requestId, String claimantId, long expectedVersion, Instant now) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_backfill_operation
                     set claim_owner=null, claim_until=null, version=version+1, updated_at=?
                     where request_id=? and claim_owner=? and version=?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, requestId);
            statement.setString(3, claimantId);
            statement.setLong(4, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new JdbcException("failed to release backfill operation", failure);
        }
    }

    @Override
    public Optional<BackfillOperation> pause(String requestId, Instant now) {
        return transition(requestId, now, Transition.PAUSE);
    }

    @Override
    public Optional<BackfillOperation> resume(String requestId, Instant now) {
        return transition(requestId, now, Transition.RESUME);
    }

    @Override
    public Optional<BackfillOperation> cancel(String requestId, Instant now) {
        return transition(requestId, now, Transition.CANCEL);
    }

    @Override
    public Optional<BackfillOperation> promote(String requestId, Instant now) {
        return transition(requestId, now, Transition.PROMOTE);
    }

    private Optional<BackfillOperation> transition(String requestId, Instant now, Transition transition) {
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BackfillOperation current = find(connection, requestId, true).orElse(null);
                if (current == null) {
                    connection.commit();
                    return Optional.empty();
                }
                BackfillProgress.BackfillStatus status = current.status();
                boolean canary = current.canaryActive();
                switch (transition) {
                    case PAUSE -> {
                        if (status == BackfillProgress.BackfillStatus.PENDING
                                || status == BackfillProgress.BackfillStatus.RUNNING) {
                            status = BackfillProgress.BackfillStatus.PAUSED;
                        }
                    }
                    case RESUME -> {
                        if (status == BackfillProgress.BackfillStatus.PAUSED
                                && (!canary || current.cursor() < current.canaryExecutions())) {
                            status = BackfillProgress.BackfillStatus.RUNNING;
                        }
                    }
                    case CANCEL -> {
                        if (!status.terminal()) status = BackfillProgress.BackfillStatus.CANCELLED;
                    }
                    case PROMOTE -> {
                        canary = false;
                        if (status == BackfillProgress.BackfillStatus.PENDING
                                || status == BackfillProgress.BackfillStatus.PAUSED) {
                            status = BackfillProgress.BackfillStatus.RUNNING;
                        }
                    }
                }
                BackfillOperation updated = copy(current, status, current.dispatched(), current.failed(),
                        current.cursor(), canary, current.nextAllowedAt(), "", null,
                        current.version() + 1, now);
                updateOperation(connection, updated, current.version(), null);
                connection.commit();
                return Optional.of(updated);
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to transition backfill operation", failure);
        }
    }

    private void insertOperation(Connection connection, BackfillOperation operation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_backfill_operation
                (request_id, job_id, root_execution_id, from_inclusive, to_inclusive, max_executions,
                 batch_size, rate_limit_per_second, canary_percent, canary_executions, status,
                 canary_active, cursor_position, expanded, dispatched, failed, definition_snapshot,
                 next_allowed_at, claim_owner, claim_until, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            BackfillRequest request = operation.request();
            BackfillOptions options = operation.options();
            statement.setString(1, request.requestId());
            statement.setString(2, request.jobId());
            statement.setString(3, request.rootExecutionId());
            statement.setTimestamp(4, Timestamp.from(request.fromInclusive()));
            statement.setTimestamp(5, Timestamp.from(request.toInclusive()));
            statement.setInt(6, request.maxExecutions());
            statement.setInt(7, options.batchSize());
            statement.setInt(8, options.rateLimitPerSecond());
            statement.setInt(9, options.canaryPercent());
            statement.setInt(10, operation.canaryExecutions());
            statement.setString(11, operation.status().name());
            statement.setBoolean(12, operation.canaryActive());
            statement.setInt(13, operation.cursor());
            statement.setInt(14, operation.expanded());
            statement.setInt(15, operation.dispatched());
            statement.setInt(16, operation.failed());
            statement.setString(17, JdbcJobRepository.encodeJobSnapshot(operation.definition()));
            statement.setTimestamp(18, Timestamp.from(operation.nextAllowedAt()));
            nullableString(statement, 19, operation.claimOwner());
            nullableTimestamp(statement, 20, operation.claimUntil());
            statement.setLong(21, operation.version());
            statement.setTimestamp(22, Timestamp.from(operation.createdAt()));
            statement.setTimestamp(23, Timestamp.from(operation.updatedAt()));
            statement.executeUpdate();
        }
    }

    private void insertItems(
            Connection connection, String requestId, List<BackfillItem> items, Instant createdAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_backfill_item
                (request_id, ordinal, fire_time, execution_id, status, error_message, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (BackfillItem item : items) {
                statement.setString(1, requestId);
                statement.setInt(2, item.ordinal());
                statement.setTimestamp(3, Timestamp.from(item.fireTime()));
                statement.setString(4, item.executionId());
                statement.setString(5, item.status().name());
                statement.setString(6, item.error());
                statement.setTimestamp(7, Timestamp.from(createdAt));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Optional<BackfillOperation> find(Connection connection, String requestId, boolean lock)
            throws SQLException {
        String sql = "select * from firefly_backfill_operation where request_id=?" + (lock ? " for update" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requestId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private BackfillOperation claim(
            Connection connection,
            BackfillOperation current,
            Instant now,
            String claimantId,
            Duration claimLease
    ) throws SQLException {
        BackfillOperation claimed = copy(current, BackfillProgress.BackfillStatus.RUNNING,
                current.dispatched(), current.failed(), current.cursor(), current.canaryActive(),
                current.nextAllowedAt(), claimantId, now.plus(claimLease), current.version() + 1, now);
        updateOperation(connection, claimed, current.version(), null);
        return claimed;
    }

    private void updateOperation(
            Connection connection, BackfillOperation operation, long expectedVersion, String expectedOwner
    ) throws SQLException {
        String sql = """
                update firefly_backfill_operation
                set status=?, canary_active=?, cursor_position=?, dispatched=?, failed=?, next_allowed_at=?,
                    claim_owner=?, claim_until=?, version=?, updated_at=?
                where request_id=? and version=?
                """ + (expectedOwner == null ? "" : " and claim_owner=?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, operation.status().name());
            statement.setBoolean(2, operation.canaryActive());
            statement.setInt(3, operation.cursor());
            statement.setInt(4, operation.dispatched());
            statement.setInt(5, operation.failed());
            statement.setTimestamp(6, Timestamp.from(operation.nextAllowedAt()));
            nullableString(statement, 7, operation.claimOwner());
            nullableTimestamp(statement, 8, operation.claimUntil());
            statement.setLong(9, operation.version());
            statement.setTimestamp(10, Timestamp.from(operation.updatedAt()));
            statement.setString(11, operation.request().requestId());
            statement.setLong(12, expectedVersion);
            if (expectedOwner != null) statement.setString(13, expectedOwner);
            if (statement.executeUpdate() != 1) throw new SQLException("backfill operation fencing rejected update");
        }
    }

    private BackfillOperation map(ResultSet resultSet) throws SQLException {
        BackfillRequest request = new BackfillRequest(
                resultSet.getString("request_id"), resultSet.getString("job_id"),
                resultSet.getTimestamp("from_inclusive").toInstant(),
                resultSet.getTimestamp("to_inclusive").toInstant(),
                resultSet.getInt("max_executions"), resultSet.getString("root_execution_id")
        );
        BackfillOptions options = new BackfillOptions(
                resultSet.getInt("batch_size"), resultSet.getInt("rate_limit_per_second"),
                resultSet.getInt("canary_percent"), Set.of()
        );
        Timestamp claimUntil = resultSet.getTimestamp("claim_until");
        return new BackfillOperation(
                request, options,
                JdbcJobRepository.decodeJobSnapshot(resultSet.getString("definition_snapshot")),
                BackfillProgress.BackfillStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("expanded"), resultSet.getInt("dispatched"),
                resultSet.getInt("failed"), resultSet.getInt("cursor_position"),
                resultSet.getInt("canary_executions"), resultSet.getBoolean("canary_active"),
                resultSet.getTimestamp("next_allowed_at").toInstant(),
                Optional.ofNullable(resultSet.getString("claim_owner")).orElse(""),
                claimUntil == null ? null : claimUntil.toInstant(), resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static BackfillOperation copy(
            BackfillOperation current,
            BackfillProgress.BackfillStatus status,
            int dispatched,
            int failed,
            int cursor,
            boolean canary,
            Instant nextAllowedAt,
            String owner,
            Instant claimUntil,
            long version,
            Instant updatedAt
    ) {
        return new BackfillOperation(current.request(), current.options(), current.definition(), status,
                current.expanded(), dispatched, failed, cursor, current.canaryExecutions(), canary,
                nextAllowedAt, owner, claimUntil, version, current.createdAt(), updatedAt);
    }

    private static boolean runnable(BackfillOperation operation, Instant now) {
        return (operation.status() == BackfillProgress.BackfillStatus.PENDING
                || operation.status() == BackfillProgress.BackfillStatus.RUNNING)
                && !operation.nextAllowedAt().isAfter(now);
    }

    private static boolean claimAvailable(BackfillOperation operation, Instant now) {
        return operation.claimOwner().isBlank() || !operation.claimUntil().isAfter(now);
    }

    private static void nullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void nullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        else statement.setTimestamp(index, Timestamp.from(value));
    }

    private static void validateClaim(Instant now, String claimantId, Duration claimLease) {
        Objects.requireNonNull(now, "now");
        if (claimantId == null || claimantId.isBlank() || claimantId.length() > 128) {
            throw new IllegalArgumentException("claimantId must be between 1 and 128 characters");
        }
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    private static boolean constraintViolation(SQLException failure) {
        return failure.getSQLState() != null && failure.getSQLState().startsWith("23");
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private enum Transition { PAUSE, RESUME, CANCEL, PROMOTE }
}
