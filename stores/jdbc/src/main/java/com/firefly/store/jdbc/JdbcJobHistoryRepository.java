package com.firefly.store.jdbc;

import com.firefly.store.JobHistoryRecord;
import com.firefly.store.JobHistoryRepository;
import com.firefly.store.JobHistoryQuery;
import com.firefly.query.CursorPage;
import com.firefly.query.PageCursor;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcJobHistoryRepository implements JobHistoryRepository {
    private final DataSource dataSource;

    public JdbcJobHistoryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void append(JobHistoryRecord record) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into firefly_job_history
                     (history_id, job_id, job_version, action_name, actor, before_payload,
                      after_payload, occurred_at)
                     values (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, record.historyId());
            statement.setString(2, record.jobId());
            statement.setLong(3, record.version());
            statement.setString(4, record.action());
            statement.setString(5, record.actor());
            statement.setString(6, record.beforePayload());
            statement.setString(7, record.afterPayload());
            statement.setTimestamp(8, Timestamp.from(record.occurredAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new JdbcException("failed to append job history", e);
        }
    }

    @Override
    public List<JobHistoryRecord> listByJob(String jobId, int limit) {
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select history_id, job_id, job_version, action_name, actor,
                            before_payload, after_payload, occurred_at
                     from firefly_job_history
                     where job_id=?
                     order by occurred_at desc, history_id desc
                     limit ?
                     """)) {
            statement.setString(1, jobId);
            statement.setInt(2, Math.max(0, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<JobHistoryRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new JobHistoryRecord(
                            resultSet.getString("history_id"), resultSet.getString("job_id"),
                            resultSet.getLong("job_version"), resultSet.getString("action_name"),
                            resultSet.getString("actor"), resultSet.getString("before_payload"),
                            resultSet.getString("after_payload"),
                            resultSet.getTimestamp("occurred_at").toInstant()
                    ));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to list job history", e);
        }
    }

    @Override
    public CursorPage<JobHistoryRecord> query(JobHistoryQuery query) {
        StringBuilder sql = new StringBuilder("""
                select history_id, job_id, job_version, action_name, actor,
                       before_payload, after_payload, occurred_at
                from firefly_job_history where job_id=?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.jobId());
        if (!query.action().isBlank()) { sql.append(" and action_name=?"); parameters.add(query.action()); }
        if (query.fromInclusive() != null) { sql.append(" and occurred_at>=?"); parameters.add(query.fromInclusive()); }
        if (query.toExclusive() != null) { sql.append(" and occurred_at<?"); parameters.add(query.toExclusive()); }
        if (query.after() != null) {
            sql.append(" and (occurred_at<? or (occurred_at=? and history_id<?))");
            parameters.add(query.after().timestamp());
            parameters.add(query.after().timestamp());
            parameters.add(query.after().id());
        }
        sql.append(" order by occurred_at desc, history_id desc limit ?");
        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                Object value = parameters.get(index);
                if (value instanceof java.time.Instant instant) statement.setTimestamp(index + 1, Timestamp.from(instant));
                else statement.setString(index + 1, value.toString());
            }
            statement.setInt(parameters.size() + 1, query.limit() + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<JobHistoryRecord> records = new ArrayList<>();
                while (resultSet.next()) records.add(map(resultSet));
                return CursorPage.of(records, query.limit(),
                        value -> new PageCursor(value.occurredAt(), value.historyId()));
            }
        } catch (SQLException failure) {
            throw new JdbcException("failed to query job history", failure);
        }
    }

    private static JobHistoryRecord map(ResultSet resultSet) throws SQLException {
        return new JobHistoryRecord(
                resultSet.getString("history_id"), resultSet.getString("job_id"),
                resultSet.getLong("job_version"), resultSet.getString("action_name"),
                resultSet.getString("actor"), resultSet.getString("before_payload"),
                resultSet.getString("after_payload"), resultSet.getTimestamp("occurred_at").toInstant()
        );
    }
}
