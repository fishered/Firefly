package com.firefly.store.jdbc;

import com.firefly.security.IntegrationKeyRecord;
import com.firefly.security.IntegrationKeyRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

/** JDBC-backed cluster-wide Integration Key digest. */
public final class JdbcIntegrationKeyRepository implements IntegrationKeyRepository {
    private static final String SYSTEM_KEY_ID = "system";
    private final DataSource dataSource;

    public JdbcIntegrationKeyRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<IntegrationKeyRecord> find() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select key_hash, version, created_at, updated_at
                     from firefly_integration_key where key_id=?
                     """)) {
            statement.setString(1, SYSTEM_KEY_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find Integration Key", e);
        }
    }

    @Override
    public boolean create(IntegrationKeyRecord record) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into firefly_integration_key
                     (key_id, key_hash, version, created_at, updated_at) values (?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, SYSTEM_KEY_ID);
            statement.setString(2, record.keyHash());
            statement.setLong(3, record.version());
            statement.setTimestamp(4, Timestamp.from(record.createdAt()));
            statement.setTimestamp(5, Timestamp.from(record.updatedAt()));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) return false;
            throw new JdbcException("failed to create Integration Key", e);
        }
    }

    @Override
    public boolean update(IntegrationKeyRecord record, long expectedVersion) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_integration_key
                     set key_hash=?, version=?, updated_at=?
                     where key_id=? and version=?
                     """)) {
            statement.setString(1, record.keyHash());
            statement.setLong(2, record.version());
            statement.setTimestamp(3, Timestamp.from(record.updatedAt()));
            statement.setString(4, SYSTEM_KEY_ID);
            statement.setLong(5, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new JdbcException("failed to update Integration Key", e);
        }
    }

    private IntegrationKeyRecord map(ResultSet resultSet) throws SQLException {
        return new IntegrationKeyRecord(
                resultSet.getString("key_hash"), resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }
}
