package com.firefly.store.jdbc;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.cluster.ShardHasher;
import com.firefly.cluster.SchedulerShardConfig;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.ExecutorCompletionPolicy;
import com.firefly.domain.ExecutorDispatchMode;
import com.firefly.domain.ExecutorRoutingStrategy;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.MisfirePolicy;
import com.firefly.domain.Schedule;
import com.firefly.store.DueJobBatch;
import com.firefly.store.DispatchOutboxRecord;
import com.firefly.store.DispatchOutboxStatus;
import com.firefly.store.DispatchType;
import com.firefly.store.JobRepository;
import com.firefly.store.ScheduledJobRecord;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.firefly.engine.ExecutionCommand;

/**
 * JDBC-backed job repository for persisted job definitions and runtime cursors.
 */
public final class JdbcJobRepository implements JobRepository {
    private static final String SCHEDULE_CRON = "CRON";
    private static final String SCHEDULE_FIXED_RATE = "FIXED_RATE";

    private final DataSource dataSource;
    private final JdbcTimeSource timeSource;
    private final int schedulerShardCount;

    public JdbcJobRepository(DataSource dataSource) {
        this(dataSource, JdbcTimeSource.database(), SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    public JdbcJobRepository(DataSource dataSource, int schedulerShardCount) {
        this(dataSource, JdbcTimeSource.database(), schedulerShardCount);
    }

    JdbcJobRepository(DataSource dataSource, JdbcTimeSource timeSource) {
        this(dataSource, timeSource, SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    JdbcJobRepository(DataSource dataSource, JdbcTimeSource timeSource, int schedulerShardCount) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.schedulerShardCount = new SchedulerShardConfig(schedulerShardCount).shardCount();
    }

    @Override
    public void save(JobDefinition definition, Instant initialNextFireTime) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(initialNextFireTime, "initialNextFireTime");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int updated = updateJob(connection, definition, initialNextFireTime);
                if (updated == 0) insertJob(connection, definition, initialNextFireTime);
                bumpConfigurationVersion(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to save firefly job", e);
        }
    }

    @Override
    public Optional<ScheduledJobRecord> find(String jobId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select *
                     from firefly_job
                     where job_id = ?
                     """)) {
            statement.setString(1, jobId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRecord(resultSet));
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find firefly job", e);
        }
    }

    @Override
    public DueJobBatch findDueBatch(Instant now, int softLimit, int hardLimit, Set<String> excludedJobIds) {
        return findDueBatch(now, softLimit, hardLimit, excludedJobIds, null);
    }

    @Override
    public DueJobBatch findDueBatchForShards(
            Instant now,
            int softLimit,
            int hardLimit,
            Set<String> excludedJobIds,
            Set<Integer> shardIds,
            int shardCount
    ) {
        if (shardCount != schedulerShardCount) {
            throw new IllegalArgumentException("unsupported scheduler shard count: " + shardCount);
        }
        return findDueBatch(now, softLimit, hardLimit, excludedJobIds, Set.copyOf(shardIds));
    }

    private DueJobBatch findDueBatch(
            Instant now,
            int softLimit,
            int hardLimit,
            Set<String> excludedJobIds,
            Set<Integer> shardIds
    ) {
        if (softLimit <= 0 || hardLimit <= 0) {
            return DueJobBatch.empty();
        }
        if (shardIds != null && shardIds.isEmpty()) {
            return DueJobBatch.empty();
        }
        Set<String> excluded = Set.copyOf(Objects.requireNonNull(excludedJobIds, "excludedJobIds"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(dueSql(excluded, shardIds))) {
            statement.setTimestamp(1, Timestamp.from(now));
            int index = 2;
            for (String excludedJobId : excluded) {
                statement.setString(index++, excludedJobId);
            }
            if (shardIds != null) {
                for (Integer shardId : shardIds.stream().sorted().toList()) {
                    statement.setInt(index++, shardId);
                }
            }
            statement.setMaxRows(hardLimit + 1);
            return mapDueBatch(statement, hardLimit);
        } catch (SQLException e) {
            throw new JdbcException("failed to find due firefly jobs", e);
        }
    }

    @Override
    public boolean updateNextFireTime(String jobId, Instant expectedCurrentNextFireTime, Instant nextFireTime) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_job
                     set next_fire_time = ?, version = version + 1
                     where job_id = ?
                       and next_fire_time = ?
                     """)) {
            /**
             * This is the repository-level CAS boundary. A scheduler only advances
             * the cursor it actually observed, which prevents duplicate progress
             * when several owners race or a stale owner comes back after failover.
             */
            statement.setTimestamp(1, Timestamp.from(nextFireTime));
            statement.setString(2, jobId);
            statement.setTimestamp(3, Timestamp.from(expectedCurrentNextFireTime));
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to update firefly job next fire time", e);
        }
    }

    @Override
    public boolean updateNextFireTimeWithLease(
            String jobId,
            Instant expectedCurrentNextFireTime,
            Instant nextFireTime,
            String ownerNodeId,
            long fencingToken
    ) {
        try (Connection connection = dataSource.getConnection()) {
            Instant databaseNow = timeSource.now(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                      update firefly_job job
                      set next_fire_time = ?, version = version + 1
                      where job.job_id = ?
                        and job.next_fire_time = ?
                        and job.next_fire_time <= ?
                        and exists (
                            select 1 from firefly_shard_lease lease
                            where lease.shard_id = job.shard_id
                              and lease.owner_node_id = ?
                              and lease.fencing_token = ?
                              and lease.lease_until >= ?
                        )
                      """)) {
                statement.setTimestamp(1, Timestamp.from(nextFireTime));
                statement.setString(2, jobId);
                statement.setTimestamp(3, Timestamp.from(expectedCurrentNextFireTime));
                statement.setTimestamp(4, Timestamp.from(databaseNow));
                statement.setString(5, ownerNodeId);
                statement.setLong(6, fencingToken);
                statement.setTimestamp(7, Timestamp.from(databaseNow));
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to update firefly job with shard lease", e);
        }
    }

    @Override
    public boolean advanceAndEnqueue(
            String jobId,
            Instant expectedCurrentNextFireTime,
            Instant nextFireTime,
            List<ExecutionCommand> commands
    ) {
        if (commands.isEmpty()) {
            return updateNextFireTime(jobId, expectedCurrentNextFireTime, nextFireTime);
        }
        ExecutionCommand owner = commands.getFirst();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Instant databaseNow = timeSource.now(connection);
                int updated;
                try (PreparedStatement statement = connection.prepareStatement("""
                        update firefly_job job set next_fire_time=?, version=version+1
                        where job.job_id=? and job.next_fire_time=?
                          and job.next_fire_time <= ? and exists (
                          select 1 from firefly_shard_lease lease
                          where lease.shard_id=job.shard_id and lease.owner_node_id=?
                            and lease.fencing_token=? and lease.lease_until>=?)
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(nextFireTime));
                    statement.setString(2, jobId);
                    statement.setTimestamp(3, Timestamp.from(expectedCurrentNextFireTime));
                    statement.setTimestamp(4, Timestamp.from(databaseNow));
                    statement.setString(5, owner.ownerNodeId());
                    statement.setLong(6, owner.fencingToken());
                    statement.setTimestamp(7, Timestamp.from(databaseNow));
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    connection.rollback();
                    return false;
                }
                if (owner.definition().concurrencyPolicy() == ConcurrencyPolicy.FORBID
                        && hasActiveExecution(connection, jobId)) {
                    connection.commit();
                    return true;
                }
                for (ExecutionCommand command : commands) {
                    insertPlannedExecution(connection, command);
                    insertOutbox(connection, command);
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to atomically advance job and enqueue dispatch", e);
        }
    }

    private boolean hasActiveExecution(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select 1 from firefly_execution
                where job_id=? and status in ('DISPATCHING','DISPATCHED','RUNNING')
                """)) {
            statement.setString(1, jobId);
            statement.setMaxRows(1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Override
    public List<DispatchOutboxRecord> claimDispatches(
            String claimant,
            Instant now,
            int limit,
            Duration claimDuration
    ) {
        return claimDispatches(
                claimant, now, limit, claimDuration, java.util.EnumSet.allOf(DispatchType.class)
        );
    }

    @Override
    public List<DispatchOutboxRecord> claimDispatches(
            String claimant,
            Instant now,
            int limit,
            Duration claimDuration,
            Set<DispatchType> dispatchTypes
    ) {
        if (dispatchTypes.isEmpty()) return List.of();
        record Candidate(String id, String rootId, int runAttempt,
                         Instant fireTime, Instant dispatchTime, String owner, long token,
                         int attempt, DispatchType dispatchType, String snapshot) {}
        String typePlaceholders = String.join(",", java.util.Collections.nCopies(dispatchTypes.size(), "?"));
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Instant databaseNow = timeSource.now(connection);
                JdbcDialect dialect = JdbcDialect.resolve(connection, JdbcSchemaOptions.auto());
                String lockClause = dialect == JdbcDialect.H2 ? "for update" : "for update skip locked";
                List<Candidate> candidates = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement("""
                        select outbox_id, root_execution_id, run_attempt, scheduled_fire_time, dispatch_time,
                               owner_node_id, fencing_token, attempt, dispatch_type, snapshot_payload
                        from firefly_dispatch_outbox
                        where dispatch_type in (%s) and available_at <= ? and (
                            status in ('PENDING','RETRY')
                            or status='SENT' and ack_deadline <= ?
                            or status='CLAIMED' and claim_until <= ?)
                        order by available_at, outbox_id
                        %s
                        """.formatted(typePlaceholders, lockClause))) {
                    int selectIndex = 1;
                    for (DispatchType dispatchType : dispatchTypes) {
                        select.setString(selectIndex++, dispatchType.name());
                    }
                    select.setTimestamp(selectIndex++, Timestamp.from(databaseNow));
                    select.setTimestamp(selectIndex++, Timestamp.from(databaseNow));
                    select.setTimestamp(selectIndex, Timestamp.from(databaseNow));
                    select.setMaxRows(limit);
                    try (ResultSet resultSet = select.executeQuery()) {
                        while (resultSet.next()) candidates.add(new Candidate(
                                resultSet.getString("outbox_id"),
                                resultSet.getString("root_execution_id"), resultSet.getInt("run_attempt"),
                                resultSet.getTimestamp("scheduled_fire_time").toInstant(),
                                resultSet.getTimestamp("dispatch_time").toInstant(),
                                resultSet.getString("owner_node_id"), resultSet.getLong("fencing_token"),
                                resultSet.getInt("attempt"),
                                DispatchType.valueOf(resultSet.getString("dispatch_type")),
                                resultSet.getString("snapshot_payload")
                        ));
                    }
                }
                List<DispatchOutboxRecord> claimed = new ArrayList<>();
                for (Candidate candidate : candidates) {
                    try (PreparedStatement update = connection.prepareStatement("""
                            update firefly_dispatch_outbox
                            set status='CLAIMED', attempt=attempt+1, claim_owner=?, claim_until=?, updated_at=?
                            where outbox_id=? and available_at <= ? and (
                              status in ('PENDING','RETRY')
                              or status='SENT' and ack_deadline <= ?
                              or status='CLAIMED' and claim_until <= ?)
                            """)) {
                        update.setString(1, claimant);
                        update.setTimestamp(2, Timestamp.from(databaseNow.plus(claimDuration)));
                        update.setTimestamp(3, Timestamp.from(databaseNow));
                        update.setString(4, candidate.id());
                        update.setTimestamp(5, Timestamp.from(databaseNow));
                        update.setTimestamp(6, Timestamp.from(databaseNow));
                        update.setTimestamp(7, Timestamp.from(databaseNow));
                        if (update.executeUpdate() == 0) continue;
                    }
                    JobDefinition definition = decodeJobSnapshot(candidate.snapshot());
                    claimed.add(new DispatchOutboxRecord(
                            candidate.id(),
                            new ExecutionCommand(candidate.id(), candidate.rootId(), candidate.runAttempt(),
                                    definition, candidate.fireTime(), candidate.dispatchTime(),
                                    candidate.owner(), candidate.token()),
                            candidate.dispatchType(), DispatchOutboxStatus.CLAIMED, candidate.attempt() + 1,
                            databaseNow, claimant, databaseNow.plus(claimDuration), null, ""
                    ));
                }
                connection.commit();
                return List.copyOf(claimed);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to claim dispatch outbox", e);
        }
    }

    @Override
    public boolean markDispatchSent(String outboxId, Instant ackDeadline) {
        try (Connection connection = dataSource.getConnection()) {
            return markDispatchSent(connection, outboxId, ackDeadline, timeSource.now(connection));
        } catch (SQLException e) {
            throw new JdbcException("failed to mark dispatch outbox sent", e);
        }
    }

    private boolean markDispatchSent(
            Connection connection,
            String outboxId,
            Instant ackDeadline,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                     update firefly_dispatch_outbox set status='SENT', ack_deadline=?, claim_until=null,
                         claim_owner=null, last_error='', updated_at=? where outbox_id=? and status='CLAIMED'
                     """)) {
            statement.setTimestamp(1, Timestamp.from(ackDeadline));
            statement.setTimestamp(2, Timestamp.from(updatedAt));
            statement.setString(3, outboxId);
            return statement.executeUpdate() > 0;
        }
    }

    @Override
    public boolean markDispatchSentFor(String outboxId, Duration ackTimeout) {
        try (Connection connection = dataSource.getConnection()) {
            Instant databaseNow = timeSource.now(connection);
            return markDispatchSent(connection, outboxId, databaseNow.plus(ackTimeout), databaseNow);
        } catch (SQLException e) {
            throw new JdbcException("failed to mark dispatch outbox sent", e);
        }
    }

    @Override
    public boolean markClaimedDispatchSentFor(
            String outboxId,
            String claimant,
            int claimAttempt,
            Duration ackTimeout
    ) {
        try (Connection connection = dataSource.getConnection()) {
            Instant databaseNow = timeSource.now(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    update firefly_dispatch_outbox
                    set status='SENT', ack_deadline=?, claim_owner=null, claim_until=null,
                        last_error='', updated_at=?
                    where outbox_id=? and status='CLAIMED' and claim_owner=? and attempt=?
                    """)) {
                statement.setTimestamp(1, Timestamp.from(databaseNow.plus(ackTimeout)));
                statement.setTimestamp(2, Timestamp.from(databaseNow));
                statement.setString(3, outboxId);
                statement.setString(4, claimant);
                statement.setInt(5, claimAttempt);
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to mark claimed dispatch outbox sent", e);
        }
    }

    @Override
    public boolean acknowledgeDispatch(String executionId, Instant now) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_dispatch_outbox set status='DONE', claim_owner=null, claim_until=null,
                         ack_deadline=null, updated_at=?
                     where execution_id=? and status in ('SENT','CLAIMED')
                     """)) {
            statement.setTimestamp(1, Timestamp.from(timeSource.now(connection)));
            statement.setString(2, executionId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to acknowledge dispatch outbox", e);
        }
    }

    @Override
    public boolean retryDispatch(String outboxId, Instant availableAt, String error, int maxAttempts) {
        try (Connection connection = dataSource.getConnection()) {
            return retryDispatch(connection, outboxId, availableAt, timeSource.now(connection), error, maxAttempts);
        } catch (SQLException e) {
            throw new JdbcException("failed to retry dispatch outbox", e);
        }
    }

    private boolean retryDispatch(
            Connection connection,
            String outboxId,
            Instant availableAt,
            Instant updatedAt,
            String error,
            int maxAttempts
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                     update firefly_dispatch_outbox
                     set status=case when attempt >= ? then 'DEAD' else 'RETRY' end,
                         available_at=?, claim_owner=null, claim_until=null, ack_deadline=null,
                         last_error=?, updated_at=?
                     where outbox_id=? and status='CLAIMED'
                     """)) {
            statement.setInt(1, maxAttempts); statement.setTimestamp(2, Timestamp.from(availableAt));
            statement.setString(3, error == null ? "" : error);
            statement.setTimestamp(4, Timestamp.from(updatedAt));
            statement.setString(5, outboxId); return statement.executeUpdate() > 0;
        }
    }

    @Override
    public boolean retryDispatchAfter(String outboxId, Duration delay, String error, int maxAttempts) {
        try (Connection connection = dataSource.getConnection()) {
            Instant databaseNow = timeSource.now(connection);
            return retryDispatch(connection, outboxId, databaseNow.plus(delay), databaseNow, error, maxAttempts);
        } catch (SQLException e) {
            throw new JdbcException("failed to retry dispatch outbox", e);
        }
    }

    @Override
    public boolean retryClaimedDispatchAfter(
            String outboxId,
            String claimant,
            int claimAttempt,
            Duration delay,
            String error,
            int maxAttempts
    ) {
        try (Connection connection = dataSource.getConnection()) {
            Instant databaseNow = timeSource.now(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    update firefly_dispatch_outbox
                    set status=case when attempt >= ? then 'DEAD' else 'RETRY' end,
                        available_at=?, claim_owner=null, claim_until=null, ack_deadline=null,
                        last_error=?, updated_at=?
                    where outbox_id=? and status='CLAIMED' and claim_owner=? and attempt=?
                    """)) {
                statement.setInt(1, maxAttempts);
                statement.setTimestamp(2, Timestamp.from(databaseNow.plus(delay)));
                statement.setString(3, error == null ? "" : error);
                statement.setTimestamp(4, Timestamp.from(databaseNow));
                statement.setString(5, outboxId);
                statement.setString(6, claimant);
                statement.setInt(7, claimAttempt);
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to retry claimed dispatch outbox", e);
        }
    }

    @Override
    public boolean completeDispatch(String outboxId, Instant now) {
        return acknowledgeDispatch(outboxId, now);
    }

    @Override
    public boolean enqueueManual(ExecutionCommand command) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertPlannedExecution(connection, command);
                insertOutbox(connection, command);
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                if ("23505".equals(e.getSQLState())) return false;
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to enqueue manual execution", e);
        }
    }

    @Override
    public boolean scheduleExecutionRetry(String sourceExecutionId, Instant requestedAt, boolean timeout) {
        record Source(String rootId, int attempt, String status, String snapshot, Instant fireTime,
                      String owner, long token, DispatchType dispatchType) {}
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Source source;
                try (PreparedStatement statement = connection.prepareStatement("""
                        select execution.root_execution_id, execution.run_attempt, execution.status,
                               outbox.snapshot_payload, outbox.scheduled_fire_time,
                               outbox.owner_node_id, outbox.fencing_token, outbox.dispatch_type
                        from firefly_execution execution
                        join firefly_dispatch_outbox outbox on outbox.execution_id=execution.execution_id
                        where execution.execution_id=? and execution.retry_scheduled=false
                        for update
                        """)) {
                    statement.setString(1, sourceExecutionId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            connection.rollback();
                            return false;
                        }
                        source = new Source(
                                resultSet.getString(1), resultSet.getInt(2), resultSet.getString(3),
                                resultSet.getString(4), resultSet.getTimestamp(5).toInstant(),
                                resultSet.getString(6), resultSet.getLong(7),
                                DispatchType.valueOf(resultSet.getString(8))
                        );
                    }
                }
                if (!java.util.Set.of("FAILED", "PARTIAL", "TIMEOUT").contains(source.status())) {
                    connection.rollback();
                    return false;
                }
                JobDefinition definition = decodeJobSnapshot(source.snapshot());
                var policy = definition.retryPolicy();
                int nextAttempt = source.attempt() + 1;
                boolean allowed = nextAttempt < policy.maxAttempts()
                        && (timeout ? policy.retryOnTimeout() : policy.retryOnFailure());
                try (PreparedStatement update = connection.prepareStatement("""
                        update firefly_execution set retry_scheduled=true, updated_at=current_timestamp
                        where execution_id=? and retry_scheduled=false
                        """)) {
                    update.setString(1, sourceExecutionId);
                    if (update.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                if (!allowed) {
                    connection.commit();
                    return false;
                }
                Instant databaseNow = timeSource.now(connection);
                String executionId = source.rootId() + "@attempt:" + nextAttempt;
                ExecutionCommand retry = new ExecutionCommand(
                        executionId, source.rootId(), nextAttempt, definition, source.fireTime(), databaseNow,
                        source.owner(), source.token()
                );
                insertPlannedExecution(connection, retry);
                insertOutbox(connection, retry, databaseNow.plus(policy.delayBeforeAttempt(nextAttempt)));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to schedule execution retry", e);
        }
    }

    private void insertPlannedExecution(Connection connection, ExecutionCommand command) throws SQLException {
        JobDefinition definition = command.definition();
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_execution
                (execution_id, root_execution_id, run_attempt, retry_scheduled, job_id,
                 scheduled_fire_time, dispatch_time, dispatch_mode, completion_policy,
                 status, expected_targets, accepted_targets, owner_node_id, fencing_token, created_at, updated_at)
                values (?, ?, ?, false, ?, ?, ?, ?, ?, 'DISPATCHING', ?, 0, ?, ?, ?, ?)
                """)) {
            Instant now = command.dispatchTime();
            statement.setString(1, command.executionId()); statement.setString(2, command.rootExecutionId());
            statement.setInt(3, command.runAttempt()); statement.setString(4, definition.id());
            statement.setTimestamp(5, Timestamp.from(command.scheduledFireTime()));
            statement.setTimestamp(6, Timestamp.from(command.dispatchTime()));
            statement.setString(7, definition.dispatchMode().name());
            statement.setString(8, definition.completionPolicy().name());
            statement.setInt(9, definition.dispatchMode() == ExecutorDispatchMode.SHARDING ? definition.shardCount() : 1);
            statement.setString(10, command.ownerNodeId()); statement.setLong(11, command.fencingToken());
            statement.setTimestamp(12, Timestamp.from(now)); statement.setTimestamp(13, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void insertOutbox(Connection connection, ExecutionCommand command) throws SQLException {
        insertOutbox(connection, command, timeSource.now(connection));
    }

    private void insertOutbox(Connection connection, ExecutionCommand command, Instant availableAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_dispatch_outbox
                (outbox_id, execution_id, root_execution_id, run_attempt, job_id,
                 scheduled_fire_time, dispatch_time, status, attempt,
                 available_at, owner_node_id, fencing_token, dispatch_type, snapshot_payload,
                 last_error, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?, ?, '', ?, ?)
                """)) {
            Instant now = timeSource.now(connection);
            statement.setString(1, command.executionId()); statement.setString(2, command.executionId());
            statement.setString(3, command.rootExecutionId()); statement.setInt(4, command.runAttempt());
            statement.setString(5, command.definition().id());
            statement.setTimestamp(6, Timestamp.from(command.scheduledFireTime()));
            statement.setTimestamp(7, Timestamp.from(command.dispatchTime()));
            statement.setTimestamp(8, Timestamp.from(availableAt)); statement.setString(9, command.ownerNodeId());
            statement.setLong(10, command.fencingToken());
            statement.setString(11, dispatchType(command.definition()).name());
            statement.setString(12, encodeJobSnapshot(command.definition()));
            statement.setTimestamp(13, Timestamp.from(now));
            statement.setTimestamp(14, Timestamp.from(now)); statement.executeUpdate();
        }
    }

    private static DispatchType dispatchType(JobDefinition definition) {
        return definition.remote() ? DispatchType.REMOTE : DispatchType.LOCAL;
    }

    static String dispatchTypeForMigration(JobDefinition definition) {
        return dispatchType(definition).name();
    }

    private static String encodeJobSnapshot(JobDefinition definition) {
        ScheduleStorage schedule = encodeSchedule(definition.schedule());
        return JdbcEncoding.encodeMap(java.util.Map.ofEntries(
                java.util.Map.entry("id", definition.id()),
                java.util.Map.entry("groupId", definition.groupId()),
                java.util.Map.entry("name", definition.name()),
                java.util.Map.entry("handlerName", definition.handlerName()),
                java.util.Map.entry("scheduleType", schedule.type()),
                java.util.Map.entry("scheduleValue", schedule.value()),
                java.util.Map.entry("zoneId", definition.zoneId().getId()),
                java.util.Map.entry("misfirePolicy", definition.misfirePolicy().name()),
                java.util.Map.entry("misfireGrace", definition.misfireGrace().toString()),
                java.util.Map.entry("concurrencyPolicy", definition.concurrencyPolicy().name()),
                java.util.Map.entry("maxCatchUpCount", Integer.toString(definition.maxCatchUpCount())),
                java.util.Map.entry("timeout", definition.timeout().toString()),
                java.util.Map.entry("parameters", JdbcEncoding.encodeMap(definition.parameters())),
                java.util.Map.entry("destinationType", definition.destination().type().name()),
                java.util.Map.entry("executorName", definition.destination().executorName()),
                java.util.Map.entry("retryMaxAttempts", Integer.toString(definition.retryPolicy().maxAttempts())),
                java.util.Map.entry("retryInitialDelay", definition.retryPolicy().initialDelay().toString()),
                java.util.Map.entry("retryMultiplier", Double.toString(definition.retryPolicy().multiplier())),
                java.util.Map.entry("retryMaxDelay", definition.retryPolicy().maxDelay().toString()),
                java.util.Map.entry("retryOnFailure", Boolean.toString(definition.retryPolicy().retryOnFailure())),
                java.util.Map.entry("retryOnTimeout", Boolean.toString(definition.retryPolicy().retryOnTimeout())),
                java.util.Map.entry("dispatchMode", definition.dispatchMode().name()),
                java.util.Map.entry("routingStrategy", definition.routingStrategy().name()),
                java.util.Map.entry("completionPolicy", definition.completionPolicy().name()),
                java.util.Map.entry("shardCount", Integer.toString(definition.shardCount())),
                java.util.Map.entry("routingKey", definition.routingKey()),
                java.util.Map.entry("enabled", Boolean.toString(definition.enabled()))
        ));
    }

    static String encodeSnapshotForMigration(JobDefinition definition) {
        return encodeJobSnapshot(definition);
    }

    static JobDefinition findDefinitionForMigration(Connection connection, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select * from firefly_job where job_id=?")) {
            statement.setString(1, jobId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new JdbcException("cannot migrate outbox without job definition: " + jobId);
                }
                return mapRecord(resultSet).definition();
            }
        }
    }

    private static JobDefinition decodeJobSnapshot(String payload) {
        java.util.Map<String, String> snapshot = JdbcEncoding.decodeMap(payload);
        if (snapshot.isEmpty()) {
            throw new JdbcException("dispatch outbox is missing its immutable job snapshot");
        }
        JobDefinition.Builder builder = JobDefinition.builder()
                .id(snapshot.get("id"))
                .groupId(snapshot.get("groupId"))
                .name(snapshot.get("name"))
                .handlerName(snapshot.get("handlerName"))
                .schedule(decodeSchedule(snapshot.get("scheduleType"), snapshot.get("scheduleValue")))
                .zoneId(ZoneId.of(snapshot.get("zoneId")))
                .misfirePolicy(MisfirePolicy.valueOf(snapshot.get("misfirePolicy")))
                .misfireGrace(Duration.parse(snapshot.get("misfireGrace")))
                .concurrencyPolicy(ConcurrencyPolicy.valueOf(snapshot.get("concurrencyPolicy")))
                .maxCatchUpCount(Integer.parseInt(snapshot.get("maxCatchUpCount")))
                .timeout(Duration.parse(snapshot.get("timeout")))
                .parameters(JdbcEncoding.decodeMap(snapshot.get("parameters")))
                .dispatchMode(ExecutorDispatchMode.valueOf(snapshot.get("dispatchMode")))
                .routingStrategy(ExecutorRoutingStrategy.valueOf(snapshot.get("routingStrategy")))
                .completionPolicy(ExecutorCompletionPolicy.valueOf(snapshot.get("completionPolicy")))
                .shardCount(Integer.parseInt(snapshot.get("shardCount")))
                .routingKey(snapshot.get("routingKey"))
                .enabled(Boolean.parseBoolean(snapshot.get("enabled")));
        String destinationType = snapshot.get("destinationType");
        if (destinationType != null) {
            builder.destination(new com.firefly.domain.JobDestination(
                    com.firefly.domain.JobDestinationType.valueOf(destinationType),
                    snapshot.getOrDefault("executorName", "")
            ));
        }
        String retryMaxAttempts = snapshot.get("retryMaxAttempts");
        if (retryMaxAttempts != null) {
            builder.retryPolicy(new com.firefly.domain.ExecutionRetryPolicy(
                    Integer.parseInt(retryMaxAttempts),
                    Duration.parse(snapshot.get("retryInitialDelay")),
                    Double.parseDouble(snapshot.get("retryMultiplier")),
                    Duration.parse(snapshot.get("retryMaxDelay")),
                    Boolean.parseBoolean(snapshot.get("retryOnFailure")),
                    Boolean.parseBoolean(snapshot.get("retryOnTimeout"))
            ));
        }
        return builder.build();
    }

    @Override
    public List<ScheduledJobRecord> list() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select *
                     from firefly_job
                     order by job_id
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            List<ScheduledJobRecord> records = new ArrayList<>();
            while (resultSet.next()) {
                records.add(mapRecord(resultSet));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new JdbcException("failed to list firefly jobs", e);
        }
    }

    @Override
    public List<ScheduledJobRecord> listForShards(Set<Integer> shardIds, int shardCount) {
        if (shardCount != schedulerShardCount) {
            throw new IllegalArgumentException("unsupported scheduler shard count: " + shardCount);
        }
        if (shardIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(shardIds.size(), "?"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from firefly_job
                     where enabled=true and shard_id in (%s)
                     order by next_fire_time, job_id
                     """.formatted(placeholders))) {
            int index = 1;
            for (Integer shardId : shardIds.stream().sorted().toList()) statement.setInt(index++, shardId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ScheduledJobRecord> records = new ArrayList<>();
                while (resultSet.next()) records.add(mapRecord(resultSet));
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list jobs for scheduler shards", e);
        }
    }

    @Override
    public long configurationVersion() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select metadata_value from firefly_cluster_metadata where metadata_key='jobs.revision'
                     """); ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) throw new JdbcException("cluster metadata jobs.revision is missing");
            return Long.parseLong(resultSet.getString(1));
        } catch (SQLException e) {
            throw new JdbcException("failed to read job configuration version", e);
        }
    }

    @Override
    public boolean setEnabled(String jobId, boolean enabled) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "update firefly_job set enabled=?, version=version+1 where job_id=?")) {
                statement.setBoolean(1, enabled);
                statement.setString(2, jobId);
                boolean updated = statement.executeUpdate() > 0;
                if (updated) bumpConfigurationVersion(connection);
                connection.commit();
                return updated;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to change job enabled state", e);
        }
    }

    @Override
    public boolean delete(String jobId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from firefly_job where job_id=?")) {
                statement.setString(1, jobId);
                boolean deleted = statement.executeUpdate() > 0;
                if (deleted) bumpConfigurationVersion(connection);
                connection.commit();
                return deleted;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to delete job", e);
        }
    }

    private void bumpConfigurationVersion(Connection connection) throws SQLException {
        long current;
        try (PreparedStatement select = connection.prepareStatement("""
                select metadata_value from firefly_cluster_metadata
                where metadata_key='jobs.revision' for update
                """); ResultSet resultSet = select.executeQuery()) {
            if (!resultSet.next()) throw new JdbcException("cluster metadata jobs.revision is missing");
            current = Long.parseLong(resultSet.getString(1));
        }
        try (PreparedStatement update = connection.prepareStatement("""
                update firefly_cluster_metadata set metadata_value=?, updated_at=current_timestamp
                where metadata_key='jobs.revision'
                """)) {
            update.setString(1, Long.toString(current + 1));
            update.executeUpdate();
        }
    }

    @Override
    public java.util.Map<DispatchOutboxStatus, Long> outboxCounts() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select status, count(*) from firefly_dispatch_outbox group by status");
             ResultSet resultSet = statement.executeQuery()) {
            java.util.EnumMap<DispatchOutboxStatus, Long> counts = new java.util.EnumMap<>(DispatchOutboxStatus.class);
            while (resultSet.next()) counts.put(DispatchOutboxStatus.valueOf(resultSet.getString(1)), resultSet.getLong(2));
            return java.util.Map.copyOf(counts);
        } catch (SQLException e) {
            throw new JdbcException("failed to count dispatch outbox", e);
        }
    }

    @Override
    public List<DispatchOutboxRecord> listDeadDispatches(int limit) {
        if (limit < 1) return List.of();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select outbox_id, execution_id, root_execution_id, run_attempt, scheduled_fire_time, dispatch_time,
                            owner_node_id, fencing_token, dispatch_type, status, attempt, available_at,
                            claim_owner, claim_until, ack_deadline, snapshot_payload, last_error
                     from firefly_dispatch_outbox
                     where status='DEAD'
                     order by updated_at desc, outbox_id
                     """)) {
            statement.setMaxRows(limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DispatchOutboxRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    JobDefinition definition = decodeJobSnapshot(resultSet.getString("snapshot_payload"));
                    records.add(new DispatchOutboxRecord(
                            resultSet.getString("outbox_id"),
                            new ExecutionCommand(
                                    resultSet.getString("execution_id"),
                                    resultSet.getString("root_execution_id"),
                                    resultSet.getInt("run_attempt"),
                                    definition,
                                    resultSet.getTimestamp("scheduled_fire_time").toInstant(),
                                    resultSet.getTimestamp("dispatch_time").toInstant(),
                                    resultSet.getString("owner_node_id"),
                                    resultSet.getLong("fencing_token")
                            ),
                            DispatchType.valueOf(resultSet.getString("dispatch_type")),
                            DispatchOutboxStatus.valueOf(resultSet.getString("status")),
                            resultSet.getInt("attempt"),
                            resultSet.getTimestamp("available_at").toInstant(),
                            resultSet.getString("claim_owner"),
                            timestampOrNull(resultSet, "claim_until"),
                            timestampOrNull(resultSet, "ack_deadline"),
                            resultSet.getString("last_error")
                    ));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list dead dispatch outbox records", e);
        }
    }

    @Override
    public boolean requeueDeadDispatch(String outboxId, Instant now) {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_dispatch_outbox
                     set status='RETRY', attempt=0, available_at=?, claim_owner=null, claim_until=null,
                         ack_deadline=null, last_error='', updated_at=?
                     where outbox_id=? and status='DEAD'
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setString(3, outboxId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new JdbcException("failed to requeue dead dispatch outbox record", e);
        }
    }

    @Override
    public Optional<Instant> oldestActiveDispatchTime() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select min(dispatch_time) from firefly_dispatch_outbox
                     where status not in ('DONE','DEAD')
                     """); ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) return Optional.empty();
            Timestamp value = resultSet.getTimestamp(1);
            return value == null ? Optional.empty() : Optional.of(value.toInstant());
        } catch (SQLException e) {
            throw new JdbcException("failed to read oldest active dispatch", e);
        }
    }

    private static Instant timestampOrNull(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private DueJobBatch mapDueBatch(PreparedStatement statement, int hardLimit) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<ScheduledJobRecord> records = new ArrayList<>(hardLimit);
            Instant fireTime = null;
            boolean truncated = false;
            while (resultSet.next()) {
                ScheduledJobRecord record = mapRecord(resultSet);
                if (fireTime == null) {
                    fireTime = record.nextFireTime();
                }
                if (!record.nextFireTime().equals(fireTime)) {
                    break;
                }
                if (records.size() >= hardLimit) {
                    truncated = true;
                    break;
                }
                records.add(record);
            }
            if (records.isEmpty()) {
                return DueJobBatch.empty();
            }
            return new DueJobBatch(fireTime, records, truncated);
        }
    }

    private String dueSql(Set<String> excludedJobIds, Set<Integer> shardIds) {
        StringBuilder sql = new StringBuilder("""
                select *
                from firefly_job
                where enabled = true
                  and next_fire_time <= ?
                """);
        if (!excludedJobIds.isEmpty()) {
            sql.append("  and job_id not in (");
            sql.append("?,".repeat(excludedJobIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")\n");
        }
        if (shardIds != null) {
            sql.append("  and shard_id in (");
            sql.append("?,".repeat(shardIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")\n");
        }
        sql.append("order by next_fire_time, job_id");
        return sql.toString();
    }

    private int updateJob(Connection connection, JobDefinition definition, Instant nextFireTime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_job
                set group_id = ?,
                    job_name = ?,
                    handler_name = ?,
                    schedule_type = ?,
                    schedule_value = ?,
                    zone_id = ?,
                    misfire_policy = ?,
                    misfire_grace = ?,
                    concurrency_policy = ?,
                    max_catch_up_count = ?,
                    timeout_value = ?,
                    parameters = ?,
                    shard_id = ?,
                    dispatch_mode = ?,
                    routing_strategy = ?,
                    completion_policy = ?,
                    shard_count = ?,
                    routing_key = ?,
                    enabled = ?,
                    next_fire_time = ?,
                    version = version + 1
                where job_id = ?
                """)) {
            bindJob(statement, definition, nextFireTime);
            statement.setString(21, definition.id());
            return statement.executeUpdate();
        }
    }

    private void insertJob(Connection connection, JobDefinition definition, Instant nextFireTime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_job
                (group_id, job_name, handler_name, schedule_type, schedule_value, zone_id,
                 misfire_policy, misfire_grace, concurrency_policy, max_catch_up_count,
                 timeout_value, parameters, shard_id, dispatch_mode, routing_strategy, completion_policy,
                 shard_count, routing_key, enabled, next_fire_time, version, job_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """)) {
            bindJob(statement, definition, nextFireTime);
            statement.setString(21, definition.id());
            statement.executeUpdate();
        }
    }

    private void bindJob(PreparedStatement statement, JobDefinition definition, Instant nextFireTime) throws SQLException {
        ScheduleStorage schedule = encodeSchedule(definition.schedule());
        statement.setString(1, definition.groupId());
        statement.setString(2, definition.name());
        statement.setString(3, definition.handlerName());
        statement.setString(4, schedule.type());
        statement.setString(5, schedule.value());
        statement.setString(6, definition.zoneId().getId());
        statement.setString(7, definition.misfirePolicy().name());
        statement.setString(8, definition.misfireGrace().toString());
        statement.setString(9, definition.concurrencyPolicy().name());
        statement.setInt(10, definition.maxCatchUpCount());
        statement.setString(11, definition.timeout().toString());
        statement.setString(12, JdbcEncoding.encodeMap(persistedParameters(definition)));
        statement.setInt(13, ShardHasher.shardFor(definition.id(), schedulerShardCount));
        statement.setString(14, definition.dispatchMode().name());
        statement.setString(15, definition.routingStrategy().name());
        statement.setString(16, definition.completionPolicy().name());
        statement.setInt(17, definition.shardCount());
        statement.setString(18, definition.routingKey());
        statement.setBoolean(19, definition.enabled());
        statement.setTimestamp(20, Timestamp.from(nextFireTime));
    }

    private static java.util.Map<String, String> persistedParameters(JobDefinition definition) {
        if (!definition.remote()) return definition.parameters();
        java.util.HashMap<String, String> parameters = new java.util.HashMap<>(definition.parameters());
        parameters.put("executorName", definition.destination().executorName());
        parameters.put("handlerName", definition.businessHandlerName());
        parameters.put("firefly.retry.maxAttempts", Integer.toString(definition.retryPolicy().maxAttempts()));
        parameters.put("firefly.retry.initialDelay", definition.retryPolicy().initialDelay().toString());
        parameters.put("firefly.retry.multiplier", Double.toString(definition.retryPolicy().multiplier()));
        parameters.put("firefly.retry.maxDelay", definition.retryPolicy().maxDelay().toString());
        parameters.put("firefly.retry.onFailure", Boolean.toString(definition.retryPolicy().retryOnFailure()));
        parameters.put("firefly.retry.onTimeout", Boolean.toString(definition.retryPolicy().retryOnTimeout()));
        return java.util.Map.copyOf(parameters);
    }

    private static ScheduledJobRecord mapRecord(ResultSet resultSet) throws SQLException {
        JobDefinition definition = JobDefinition.builder()
                .id(resultSet.getString("job_id"))
                .groupId(resultSet.getString("group_id"))
                .name(resultSet.getString("job_name"))
                .handlerName(resultSet.getString("handler_name"))
                .schedule(decodeSchedule(
                        resultSet.getString("schedule_type"),
                        resultSet.getString("schedule_value")
                ))
                .zoneId(ZoneId.of(resultSet.getString("zone_id")))
                .misfirePolicy(MisfirePolicy.valueOf(resultSet.getString("misfire_policy")))
                .misfireGrace(Duration.parse(resultSet.getString("misfire_grace")))
                .concurrencyPolicy(ConcurrencyPolicy.valueOf(resultSet.getString("concurrency_policy")))
                .maxCatchUpCount(resultSet.getInt("max_catch_up_count"))
                .timeout(Duration.parse(resultSet.getString("timeout_value")))
                .parameters(JdbcEncoding.decodeMap(resultSet.getString("parameters")))
                .dispatchMode(ExecutorDispatchMode.valueOf(resultSet.getString("dispatch_mode")))
                .routingStrategy(ExecutorRoutingStrategy.valueOf(resultSet.getString("routing_strategy")))
                .completionPolicy(ExecutorCompletionPolicy.valueOf(resultSet.getString("completion_policy")))
                .shardCount(resultSet.getInt("shard_count"))
                .routingKey(resultSet.getString("routing_key"))
                .enabled(resultSet.getBoolean("enabled"))
                .build();
        return new ScheduledJobRecord(definition, resultSet.getTimestamp("next_fire_time").toInstant());
    }

    private static ScheduleStorage encodeSchedule(Schedule schedule) {
        if (schedule instanceof CronSchedule cronSchedule) {
            return new ScheduleStorage(SCHEDULE_CRON, cronSchedule.expression());
        }
        if (schedule instanceof FixedRateSchedule fixedRateSchedule) {
            return new ScheduleStorage(SCHEDULE_FIXED_RATE, fixedRateSchedule.interval().toString());
        }
        throw new IllegalArgumentException("unsupported schedule type: " + schedule.getClass().getName());
    }

    private static Schedule decodeSchedule(String type, String value) {
        return switch (type) {
            case SCHEDULE_CRON -> new CronSchedule(value);
            case SCHEDULE_FIXED_RATE -> new FixedRateSchedule(Duration.parse(value));
            default -> throw new IllegalArgumentException("unsupported schedule type: " + type);
        };
    }

    private record ScheduleStorage(String type, String value) {
    }
}
