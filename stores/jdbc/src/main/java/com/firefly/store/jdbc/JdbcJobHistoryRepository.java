package com.firefly.store.jdbc;

import com.firefly.store.JobHistoryRecord;
import com.firefly.store.JobHistoryRepository;

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
}
