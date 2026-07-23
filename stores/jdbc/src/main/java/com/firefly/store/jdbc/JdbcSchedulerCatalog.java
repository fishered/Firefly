package com.firefly.store.jdbc;

import com.firefly.catalog.SchedulerCatalog;
import com.firefly.cluster.SchedulerShardConfig;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobGroupDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JDBC storage for stable scheduler configuration. Runtime executor liveness remains in the gateway registry.
 */
public final class JdbcSchedulerCatalog implements SchedulerCatalog {
    private final DataSource dataSource;
    private final JdbcJobRepository jobRepository;
    private final JdbcTimeSource timeSource;

    public JdbcSchedulerCatalog(DataSource dataSource) {
        this(dataSource, JdbcTimeSource.database(), SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    public JdbcSchedulerCatalog(DataSource dataSource, int schedulerShardCount) {
        this(dataSource, JdbcTimeSource.database(), schedulerShardCount);
    }

    JdbcSchedulerCatalog(DataSource dataSource, JdbcTimeSource timeSource) {
        this(dataSource, timeSource, SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    JdbcSchedulerCatalog(DataSource dataSource, JdbcTimeSource timeSource, int schedulerShardCount) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.jobRepository = new JdbcJobRepository(dataSource, timeSource, schedulerShardCount);
    }

    @Override
    public void saveExecutor(ExecutorDefinition executor) {
        Objects.requireNonNull(executor, "executor");
        try (Connection connection = dataSource.getConnection()) {
            int updated = updateExecutor(connection, executor);
            if (updated == 0) {
                insertExecutor(connection, executor);
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to save executor definition", e);
        }
    }

    @Override
    public Optional<ExecutorDefinition> findExecutor(String name) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select executor_name, description, protocols, metadata, enabled
                     from firefly_executor
                     where executor_name = ?
                     """)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapExecutor(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find executor definition", e);
        }
    }

    @Override
    public List<ExecutorDefinition> listExecutors() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select executor_name, description, protocols, metadata, enabled
                     from firefly_executor
                     order by executor_name
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            List<ExecutorDefinition> executors = new ArrayList<>();
            while (resultSet.next()) {
                executors.add(mapExecutor(resultSet));
            }
            return List.copyOf(executors);
        } catch (SQLException e) {
            throw new JdbcException("failed to list executor definitions", e);
        }
    }

    @Override
    public boolean deleteExecutor(String name) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     delete from firefly_executor
                     where executor_name = ?
                     """)) {
            statement.setString(1, name);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new JdbcException("failed to delete executor definition", e);
        }
    }

    @Override
    public void saveJobGroup(JobGroupDefinition group) {
        Objects.requireNonNull(group, "group");
        try (Connection connection = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    update firefly_job_group set group_name=?, executor_name=?, metadata=?, enabled=?
                    where group_id=?
                    """)) {
                bindGroup(statement, group, 1);
                statement.setString(5, group.id());
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert into firefly_job_group
                        (group_id, group_name, executor_name, metadata, enabled) values (?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, group.id());
                    bindGroup(statement, group, 2);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to save job group", e);
        }
    }

    @Override
    public Optional<JobGroupDefinition> findJobGroup(String groupId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select group_id, group_name, executor_name, metadata, enabled
                     from firefly_job_group where group_id=?
                     """)) {
            statement.setString(1, groupId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapGroup(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find job group", e);
        }
    }

    @Override
    public List<JobGroupDefinition> listJobGroups() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select group_id, group_name, executor_name, metadata, enabled
                     from firefly_job_group
                     order by group_id
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            List<JobGroupDefinition> groups = new ArrayList<>();
            while (resultSet.next()) {
                groups.add(mapGroup(resultSet));
            }
            return List.copyOf(groups);
        } catch (SQLException e) {
            throw new JdbcException("failed to list job groups", e);
        }
    }

    @Override
    public void saveJob(JobDefinition definition) {
        try (Connection connection = dataSource.getConnection()) {
            java.time.Instant now = timeSource.now(connection);
            jobRepository.save(definition, definition.schedule().nextAfter(now, definition.zoneId()));
        } catch (SQLException e) {
            throw new JdbcException("failed to save catalog job", e);
        }
    }

    @Override
    public Optional<JobDefinition> findJob(String jobId) {
        return jobRepository.find(jobId).map(com.firefly.store.ScheduledJobRecord::definition);
    }

    @Override
    public List<JobDefinition> listJobsByGroup(String groupId) {
        return jobRepository.list().stream()
                .map(com.firefly.store.ScheduledJobRecord::definition)
                .filter(job -> job.groupId().equals(groupId))
                .toList();
    }

    private void bindGroup(PreparedStatement statement, JobGroupDefinition group, int offset) throws SQLException {
        statement.setString(offset, group.name());
        statement.setString(offset + 1, group.executorName());
        statement.setString(offset + 2, JdbcEncoding.encodeMap(group.metadata()));
        statement.setBoolean(offset + 3, group.enabled());
    }

    private JobGroupDefinition mapGroup(ResultSet resultSet) throws SQLException {
        return new JobGroupDefinition(
                resultSet.getString("group_id"),
                resultSet.getString("group_name"),
                resultSet.getString("executor_name"),
                JdbcEncoding.decodeMap(resultSet.getString("metadata")),
                resultSet.getBoolean("enabled")
        );
    }

    private int updateExecutor(Connection connection, ExecutorDefinition executor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update firefly_executor
                set description = ?, protocols = ?, metadata = ?, enabled = ?
                where executor_name = ?
                """)) {
            bindExecutor(statement, executor);
            statement.setString(5, executor.name());
            return statement.executeUpdate();
        }
    }

    private void insertExecutor(Connection connection, ExecutorDefinition executor) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into firefly_executor (executor_name, description, protocols, metadata, enabled)
                values (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, executor.name());
            bindExecutor(statement, executor, 2);
            statement.executeUpdate();
        }
    }

    private void bindExecutor(PreparedStatement statement, ExecutorDefinition executor) throws SQLException {
        bindExecutor(statement, executor, 1);
    }

    private void bindExecutor(PreparedStatement statement, ExecutorDefinition executor, int offset) throws SQLException {
        statement.setString(offset, executor.description());
        statement.setString(offset + 1, executor.protocols().stream()
                .map(ExecutorProtocol::name)
                .sorted()
                .collect(Collectors.joining(",")));
        statement.setString(offset + 2, JdbcEncoding.encodeMap(executor.metadata()));
        statement.setBoolean(offset + 3, executor.enabled());
    }

    private ExecutorDefinition mapExecutor(ResultSet resultSet) throws SQLException {
        Set<ExecutorProtocol> protocols = java.util.Arrays.stream(resultSet.getString("protocols").split(","))
                .filter(value -> !value.isBlank())
                .map(ExecutorProtocol::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        return new ExecutorDefinition(
                resultSet.getString("executor_name"),
                resultSet.getString("description"),
                protocols,
                JdbcEncoding.decodeMap(resultSet.getString("metadata")),
                resultSet.getBoolean("enabled")
        );
    }
}
