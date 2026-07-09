package com.firefly.store.jdbc;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.MisfirePolicy;
import com.firefly.domain.Schedule;
import com.firefly.store.DueJobBatch;
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

/**
 * JDBC-backed job repository for persisted job definitions and runtime cursors.
 */
public final class JdbcJobRepository implements JobRepository {
    private static final String SCHEDULE_CRON = "CRON";
    private static final String SCHEDULE_FIXED_RATE = "FIXED_RATE";

    private final DataSource dataSource;

    public JdbcJobRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void save(JobDefinition definition, Instant initialNextFireTime) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(initialNextFireTime, "initialNextFireTime");
        try (Connection connection = dataSource.getConnection()) {
            int updated = updateJob(connection, definition, initialNextFireTime);
            if (updated == 0) {
                insertJob(connection, definition, initialNextFireTime);
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
        if (softLimit <= 0 || hardLimit <= 0) {
            return DueJobBatch.empty();
        }
        Set<String> excluded = Set.copyOf(Objects.requireNonNull(excludedJobIds, "excludedJobIds"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(dueSql(excluded))) {
            statement.setTimestamp(1, Timestamp.from(now));
            int index = 2;
            for (String excludedJobId : excluded) {
                statement.setString(index++, excludedJobId);
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

    private String dueSql(Set<String> excludedJobIds) {
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
                    enabled = ?,
                    next_fire_time = ?,
                    version = version + 1
                where job_id = ?
                """)) {
            bindJob(statement, definition, nextFireTime);
            statement.setString(15, definition.id());
            return statement.executeUpdate();
        }
    }

    private void insertJob(Connection connection, JobDefinition definition, Instant nextFireTime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_job
                (group_id, job_name, handler_name, schedule_type, schedule_value, zone_id,
                 misfire_policy, misfire_grace, concurrency_policy, max_catch_up_count,
                 timeout_value, parameters, enabled, next_fire_time, version, job_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """)) {
            bindJob(statement, definition, nextFireTime);
            statement.setString(15, definition.id());
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
        statement.setString(12, JdbcEncoding.encodeMap(definition.parameters()));
        statement.setBoolean(13, definition.enabled());
        statement.setTimestamp(14, Timestamp.from(nextFireTime));
    }

    private ScheduledJobRecord mapRecord(ResultSet resultSet) throws SQLException {
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
                .enabled(resultSet.getBoolean("enabled"))
                .build();
        return new ScheduledJobRecord(definition, resultSet.getTimestamp("next_fire_time").toInstant());
    }

    private ScheduleStorage encodeSchedule(Schedule schedule) {
        if (schedule instanceof CronSchedule cronSchedule) {
            return new ScheduleStorage(SCHEDULE_CRON, cronSchedule.expression());
        }
        if (schedule instanceof FixedRateSchedule fixedRateSchedule) {
            return new ScheduleStorage(SCHEDULE_FIXED_RATE, fixedRateSchedule.interval().toString());
        }
        throw new IllegalArgumentException("unsupported schedule type: " + schedule.getClass().getName());
    }

    private Schedule decodeSchedule(String type, String value) {
        return switch (type) {
            case SCHEDULE_CRON -> new CronSchedule(value);
            case SCHEDULE_FIXED_RATE -> new FixedRateSchedule(Duration.parse(value));
            default -> throw new IllegalArgumentException("unsupported schedule type: " + type);
        };
    }

    private record ScheduleStorage(String type, String value) {
    }
}
