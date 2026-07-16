package com.firefly.store.jdbc;

import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.execution.ExecutionRecord;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.ExecutionStatus;
import com.firefly.execution.ExecutionTargetRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JDBC execution history with parent status aggregation for broadcast and sharded work. */
public final class JdbcExecutionRepository implements ExecutionRepository {
    private final DataSource dataSource;
    private final JdbcTimeSource timeSource;

    public JdbcExecutionRepository(DataSource dataSource) {
        this(dataSource, JdbcTimeSource.database());
    }

    JdbcExecutionRepository(DataSource dataSource, JdbcTimeSource timeSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    @Override
    public void saveExecution(ExecutionRecord execution) {
        try (Connection connection = dataSource.getConnection()) {
            saveExecution(connection, execution);
        } catch (SQLException e) {
            throw new JdbcException("failed to save execution", e);
        }
    }

    @Override
    public void startExecution(ExecutionRecord execution, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try (Connection connection = dataSource.getConnection()) {
            Instant databaseNow = timeSource.now(connection);
            saveExecution(connection, new ExecutionRecord(
                    execution.executionId(), execution.rootExecutionId(), execution.runAttempt(), execution.jobId(),
                    execution.scheduledFireTime(), execution.dispatchTime(), execution.dispatchMode(),
                    execution.completionPolicy(), execution.status(), execution.expectedTargets(),
                    execution.acceptedTargets(), execution.ownerNodeId(), execution.fencingToken(),
                    databaseNow.plus(timeout), execution.createdAt(), databaseNow
            ));
        } catch (SQLException e) {
            throw new JdbcException("failed to start execution", e);
        }
    }

    @Override
    public void saveTargets(List<ExecutionTargetRecord> targets) {
        try (Connection connection = dataSource.getConnection()) {
            for (ExecutionTargetRecord target : targets) {
                if (updateTarget(connection, target) == 0 && !targetExists(connection, target.targetExecutionId())) {
                    insertTarget(connection, target);
                }
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to save execution targets", e);
        }
    }

    @Override
    public com.firefly.execution.ExecutionMutationResult acknowledgeResult(
            String targetExecutionId, Instant acknowledgedAt
    ) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String executionId = findParentId(connection, targetExecutionId).orElse(null);
                if (executionId == null) {
                    connection.rollback();
                    return com.firefly.execution.ExecutionMutationResult.REJECTED;
                }
                ExecutionRecord parent = lockExecution(connection, executionId).orElse(null);
                boolean updated = false;
                if (parent != null && !parent.status().terminal()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            update firefly_execution_target
                            set status='RUNNING', acknowledged_at=?, updated_at=?
                            where target_execution_id=? and status='DISPATCHED'
                              and acknowledged_at is null and completed_at is null
                            """)) {
                        statement.setTimestamp(1, Timestamp.from(acknowledgedAt));
                        statement.setTimestamp(2, Timestamp.from(acknowledgedAt));
                        statement.setString(3, targetExecutionId);
                        updated = statement.executeUpdate() > 0;
                    }
                    if (updated) refreshParentLocked(connection, parent, acknowledgedAt);
                }
                com.firefly.execution.ExecutionMutationResult result = updated
                        ? com.firefly.execution.ExecutionMutationResult.APPLIED
                        : targetDelivered(connection, targetExecutionId)
                        ? com.firefly.execution.ExecutionMutationResult.ALREADY_APPLIED
                        : com.firefly.execution.ExecutionMutationResult.REJECTED;
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to acknowledge execution target", e);
        }
    }

    @Override
    public com.firefly.execution.ExecutionMutationResult completeResult(
            String targetExecutionId, ExecutionStatus status, String errorMessage, Instant completedAt
    ) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("target completion status must be terminal");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String executionId = findParentId(connection, targetExecutionId).orElse(null);
                if (executionId == null) {
                    connection.rollback();
                    return com.firefly.execution.ExecutionMutationResult.REJECTED;
                }
                ExecutionRecord parent = lockExecution(connection, executionId).orElse(null);
                boolean updated = false;
                if (parent != null && !parent.status().terminal()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            update firefly_execution_target
                            set status=?, acknowledged_at=coalesce(acknowledged_at, ?), completed_at=?,
                                error_message=?, updated_at=?
                            where target_execution_id=? and status in ('DISPATCHED','RUNNING')
                              and completed_at is null
                            """)) {
                        statement.setString(1, status.name());
                        statement.setTimestamp(2, Timestamp.from(completedAt));
                        statement.setTimestamp(3, Timestamp.from(completedAt));
                        statement.setString(4, errorMessage == null ? "" : errorMessage);
                        statement.setTimestamp(5, Timestamp.from(completedAt));
                        statement.setString(6, targetExecutionId);
                        updated = statement.executeUpdate() > 0;
                    }
                    if (updated) refreshParentLocked(connection, parent, completedAt);
                }
                com.firefly.execution.ExecutionMutationResult result = updated
                        ? com.firefly.execution.ExecutionMutationResult.APPLIED
                        : targetCompletionMatches(connection, targetExecutionId, status)
                        ? com.firefly.execution.ExecutionMutationResult.ALREADY_APPLIED
                        : com.firefly.execution.ExecutionMutationResult.REJECTED;
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to complete execution target", e);
        }
    }

    @Override
    public Optional<ExecutionRecord> findExecution(String executionId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select * from firefly_execution where execution_id = ?")) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapExecution(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find execution", e);
        }
    }

    @Override
    public List<ExecutionTargetRecord> listTargets(String executionId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from firefly_execution_target
                     where execution_id = ? order by target_execution_id
                     """)) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionTargetRecord> records = new ArrayList<>();
                while (resultSet.next()) records.add(mapTarget(resultSet));
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list execution targets", e);
        }
    }

    @Override
    public List<ExecutionRecord> listRecent(int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from firefly_execution order by created_at desc
                     """)) {
            statement.setMaxRows(Math.max(0, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionRecord> records = new ArrayList<>();
                while (resultSet.next()) records.add(mapExecution(resultSet));
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list executions", e);
        }
    }

    @Override
    public List<String> expireTimedOutExecutions(Instant now, int limit) {
        if (limit <= 0) return List.of();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Instant databaseNow = timeSource.now(connection);
                List<String> candidates = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        select execution_id from firefly_execution
                        where status in ('DISPATCHING','DISPATCHED','RUNNING')
                          and timeout_at is not null and timeout_at <= ?
                        order by timeout_at, execution_id
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(databaseNow));
                    statement.setMaxRows(limit);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) candidates.add(resultSet.getString(1));
                    }
                }
                List<String> expired = new ArrayList<>();
                for (String executionId : candidates) {
                    ExecutionRecord current = lockExecution(connection, executionId).orElse(null);
                    if (current == null || current.status().terminal() || current.timeoutAt() == null
                            || current.timeoutAt().isAfter(databaseNow)) {
                        continue;
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                            update firefly_execution set status='TIMEOUT', updated_at=?
                            where execution_id=? and status in ('DISPATCHING','DISPATCHED','RUNNING')
                              and timeout_at <= ?
                            """)) {
                        update.setTimestamp(1, Timestamp.from(databaseNow));
                        update.setString(2, executionId);
                        update.setTimestamp(3, Timestamp.from(databaseNow));
                        if (update.executeUpdate() == 0) continue;
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                            update firefly_execution_target
                            set status='TIMEOUT', completed_at=?, error_message='execution timeout', updated_at=?
                            where execution_id=? and status in ('DISPATCHED','RUNNING') and completed_at is null
                            """)) {
                        update.setTimestamp(1, Timestamp.from(databaseNow));
                        update.setTimestamp(2, Timestamp.from(databaseNow));
                        update.setString(3, executionId);
                        update.executeUpdate();
                    }
                    try (PreparedStatement update = connection.prepareStatement("""
                            update firefly_dispatch_outbox
                            set status='DEAD', claim_until=null, ack_deadline=null,
                                last_error='execution timeout', updated_at=?
                            where execution_id=? and status not in ('DONE','DEAD')
                            """)) {
                        update.setTimestamp(1, Timestamp.from(databaseNow));
                        update.setString(2, executionId);
                        update.executeUpdate();
                    }
                    expired.add(executionId);
                }
                connection.commit();
                return List.copyOf(expired);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to expire timed out executions", e);
        }
    }

    @Override
    public int deleteCompletedBefore(Instant cutoff, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    select execution_id from firefly_execution
                    where updated_at < ? and status in ('SUCCEEDED','FAILED','PARTIAL','TIMEOUT')
                    order by updated_at
                    """)) {
                select.setTimestamp(1, Timestamp.from(cutoff)); select.setMaxRows(limit);
                try (ResultSet resultSet = select.executeQuery()) {
                    while (resultSet.next()) ids.add(resultSet.getString(1));
                }
            }
            for (String id : ids) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "delete from firefly_execution_target where execution_id=?")) {
                    delete.setString(1, id); delete.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "delete from firefly_dispatch_outbox where execution_id=?")) {
                    delete.setString(1, id); delete.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "delete from firefly_execution where execution_id=?")) {
                    delete.setString(1, id); delete.executeUpdate();
                }
            }
            return ids.size();
        } catch (SQLException e) {
            throw new JdbcException("failed to delete execution history", e);
        }
    }

    @Override
    public java.util.Map<ExecutionStatus, Long> statusCounts() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select status, count(*) from firefly_execution group by status");
             ResultSet resultSet = statement.executeQuery()) {
            java.util.EnumMap<ExecutionStatus, Long> counts = new java.util.EnumMap<>(ExecutionStatus.class);
            while (resultSet.next()) counts.put(ExecutionStatus.valueOf(resultSet.getString(1)), resultSet.getLong(2));
            return java.util.Map.copyOf(counts);
        } catch (SQLException e) {
            throw new JdbcException("failed to count executions", e);
        }
    }

    private void saveExecution(Connection connection, ExecutionRecord execution) throws SQLException {
        int updated = updateExecution(connection, execution);
        if (updated == 0 && !executionExists(connection, execution.executionId())) {
            insertExecution(connection, execution);
        }
    }

    private int updateExecution(Connection connection, ExecutionRecord value) throws SQLException {
        List<ExecutionStatus> predecessors = allowedPredecessors(value.status());
        String placeholders = String.join(",", java.util.Collections.nCopies(predecessors.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_execution set status=?,
                    expected_targets=case when expected_targets > ? then expected_targets else ? end,
                    accepted_targets=case when accepted_targets > ? then accepted_targets else ? end,
                    timeout_at=coalesce(timeout_at, ?), updated_at=?
                where execution_id=? and status in (%s)
                """.formatted(placeholders))) {
            statement.setString(1, value.status().name());
            statement.setInt(2, value.expectedTargets());
            statement.setInt(3, value.expectedTargets());
            statement.setInt(4, value.acceptedTargets());
            statement.setInt(5, value.acceptedTargets());
            bindNullable(statement, 6, value.timeoutAt());
            statement.setTimestamp(7, Timestamp.from(value.updatedAt()));
            statement.setString(8, value.executionId());
            int index = 9;
            for (ExecutionStatus predecessor : predecessors) statement.setString(index++, predecessor.name());
            return statement.executeUpdate();
        }
    }

    private List<ExecutionStatus> allowedPredecessors(ExecutionStatus next) {
        return switch (next) {
            case DISPATCHING -> List.of(ExecutionStatus.DISPATCHING);
            case DISPATCHED -> List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.DISPATCHED);
            case RUNNING -> List.of(ExecutionStatus.DISPATCHING, ExecutionStatus.DISPATCHED, ExecutionStatus.RUNNING);
            case SUCCEEDED, PARTIAL, FAILED, TIMEOUT -> List.of(
                    ExecutionStatus.DISPATCHING, ExecutionStatus.DISPATCHED, ExecutionStatus.RUNNING, next
            );
        };
    }

    private boolean executionExists(Connection connection, String executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from firefly_execution where execution_id=?")) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insertExecution(Connection connection, ExecutionRecord value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_execution
                (execution_id, root_execution_id, run_attempt, retry_scheduled, job_id,
                 scheduled_fire_time, dispatch_time, dispatch_mode, completion_policy,
                  status, expected_targets, accepted_targets, owner_node_id, fencing_token, timeout_at,
                  created_at, updated_at)
                values (?, ?, ?, false, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, value.executionId()); statement.setString(2, value.rootExecutionId());
            statement.setInt(3, value.runAttempt()); statement.setString(4, value.jobId());
            statement.setTimestamp(5, Timestamp.from(value.scheduledFireTime()));
            statement.setTimestamp(6, Timestamp.from(value.dispatchTime()));
            statement.setString(7, value.dispatchMode().name()); statement.setString(8, value.completionPolicy().name());
            statement.setString(9, value.status().name()); statement.setInt(10, value.expectedTargets());
            statement.setInt(11, value.acceptedTargets()); statement.setString(12, value.ownerNodeId());
            statement.setLong(13, value.fencingToken()); bindNullable(statement, 14, value.timeoutAt());
            statement.setTimestamp(15, Timestamp.from(value.createdAt()));
            statement.setTimestamp(16, Timestamp.from(value.updatedAt())); statement.executeUpdate();
        }
    }

    private int updateTarget(Connection connection, ExecutionTargetRecord value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_execution_target set instance_id=?, gateway_node_id=?, shard_index=?, status=?,
                    attempt=attempt+1, updated_at=?
                where target_execution_id=? and acknowledged_at is null and completed_at is null
                """)) {
            statement.setString(1, value.instanceId()); statement.setString(2, value.gatewayNodeId());
            if (value.shardIndex() == null) statement.setNull(3, java.sql.Types.INTEGER); else statement.setInt(3, value.shardIndex());
            statement.setString(4, value.status().name());
            statement.setTimestamp(5, Timestamp.from(value.updatedAt()));
            statement.setString(6, value.targetExecutionId());
            return statement.executeUpdate();
        }
    }

    private boolean targetExists(Connection connection, String targetExecutionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from firefly_execution_target where target_execution_id=?")) {
            statement.setString(1, targetExecutionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void insertTarget(Connection connection, ExecutionTargetRecord value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_execution_target
                (target_execution_id, execution_id, instance_id, gateway_node_id, shard_index, status, attempt,
                 acknowledged_at, completed_at, error_message, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, value.targetExecutionId()); statement.setString(2, value.executionId());
            statement.setString(3, value.instanceId()); statement.setString(4, value.gatewayNodeId());
            if (value.shardIndex() == null) statement.setNull(5, java.sql.Types.INTEGER); else statement.setInt(5, value.shardIndex());
            statement.setString(6, value.status().name()); statement.setInt(7, value.attempt());
            bindNullable(statement, 8, value.acknowledgedAt()); bindNullable(statement, 9, value.completedAt());
            statement.setString(10, value.errorMessage()); statement.setTimestamp(11, Timestamp.from(value.createdAt()));
            statement.setTimestamp(12, Timestamp.from(value.updatedAt())); statement.executeUpdate();
        }
    }

    private Optional<String> findParentId(Connection connection, String targetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select execution_id from firefly_execution_target where target_execution_id = ?
                """)) {
            statement.setString(1, targetId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getString(1)) : Optional.empty();
            }
        }
    }

    private Optional<ExecutionRecord> lockExecution(Connection connection, String executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select * from firefly_execution where execution_id=? for update")) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapExecution(resultSet)) : Optional.empty();
            }
        }
    }

    private boolean targetDelivered(Connection connection, String targetExecutionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select acknowledged_at, completed_at from firefly_execution_target
                where target_execution_id=?
                """)) {
            statement.setString(1, targetExecutionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && (resultSet.getTimestamp(1) != null || resultSet.getTimestamp(2) != null);
            }
        }
    }

    private boolean targetCompletionMatches(
            Connection connection, String targetExecutionId, ExecutionStatus status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select status, completed_at from firefly_execution_target where target_execution_id=?
                """)) {
            statement.setString(1, targetExecutionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getTimestamp(2) != null
                        && status.name().equals(resultSet.getString(1));
            }
        }
    }

    private void refreshParentLocked(Connection connection, ExecutionRecord parent, Instant now) throws SQLException {
        if (parent.status().terminal()) return;
        String executionId = parent.executionId();
        int succeeded = 0, failed = 0, running = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                select status, count(*) total from firefly_execution_target where execution_id=? group by status
                """)) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ExecutionStatus status = ExecutionStatus.valueOf(resultSet.getString(1));
                    int count = resultSet.getInt(2);
                    if (status == ExecutionStatus.SUCCEEDED) succeeded += count;
                    else if (status == ExecutionStatus.FAILED || status == ExecutionStatus.TIMEOUT) failed += count;
                    else if (status == ExecutionStatus.RUNNING) running += count;
                }
            }
        }
        int completed = succeeded + failed;
        ExecutionStatus status;
        if (parent.completionPolicy() == ExecutorCompletionPolicy.ANY_SUCCESS && succeeded > 0) status = ExecutionStatus.SUCCEEDED;
        else if (parent.completionPolicy() == ExecutorCompletionPolicy.QUORUM
                && succeeded >= parent.expectedTargets() / 2 + 1) status = ExecutionStatus.SUCCEEDED;
        else if (completed < parent.expectedTargets()) status = running > 0 ? ExecutionStatus.RUNNING : ExecutionStatus.DISPATCHED;
        else if (parent.completionPolicy() == ExecutorCompletionPolicy.ANY_SUCCESS) status = succeeded > 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        else if (parent.completionPolicy() == ExecutorCompletionPolicy.QUORUM) status = succeeded >= parent.expectedTargets() / 2 + 1 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        else status = failed == 0 ? ExecutionStatus.SUCCEEDED : succeeded == 0 ? ExecutionStatus.FAILED : ExecutionStatus.PARTIAL;
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_execution set status=?, updated_at=?
                where execution_id=? and status in ('DISPATCHING','DISPATCHED','RUNNING')
                """)) {
            statement.setString(1, status.name()); statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, executionId); statement.executeUpdate();
        }
    }

    private ExecutionRecord mapExecution(ResultSet resultSet) throws SQLException {
        return new ExecutionRecord(
                resultSet.getString("execution_id"), resultSet.getString("root_execution_id"),
                resultSet.getInt("run_attempt"), resultSet.getString("job_id"),
                resultSet.getTimestamp("scheduled_fire_time").toInstant(), resultSet.getTimestamp("dispatch_time").toInstant(),
                ExecutorDispatchMode.valueOf(resultSet.getString("dispatch_mode")),
                ExecutorCompletionPolicy.valueOf(resultSet.getString("completion_policy")),
                ExecutionStatus.valueOf(resultSet.getString("status")), resultSet.getInt("expected_targets"),
                resultSet.getInt("accepted_targets"), resultSet.getString("owner_node_id"),
                resultSet.getLong("fencing_token"), instant(resultSet, "timeout_at"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private ExecutionTargetRecord mapTarget(ResultSet resultSet) throws SQLException {
        int shard = resultSet.getInt("shard_index");
        Integer shardIndex = resultSet.wasNull() ? null : shard;
        return new ExecutionTargetRecord(
                resultSet.getString("target_execution_id"), resultSet.getString("execution_id"),
                resultSet.getString("instance_id"), resultSet.getString("gateway_node_id"), shardIndex,
                ExecutionStatus.valueOf(resultSet.getString("status")), resultSet.getInt("attempt"),
                instant(resultSet, "acknowledged_at"), instant(resultSet, "completed_at"),
                resultSet.getString("error_message"), resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private void bindNullable(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.TIMESTAMP);
        else statement.setTimestamp(index, Timestamp.from(value));
    }
}
