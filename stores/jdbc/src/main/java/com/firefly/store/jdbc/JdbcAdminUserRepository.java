package com.firefly.store.jdbc;

import com.firefly.security.AdminUser;
import com.firefly.security.AdminUserRepository;
import com.firefly.security.FireflyRole;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** JDBC-backed Admin identities shared by every API node. */
public final class JdbcAdminUserRepository implements AdminUserRepository {
    private final DataSource dataSource;

    public JdbcAdminUserRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<AdminUser> find(String username) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select username, password_hash, roles, enabled, version, created_at, updated_at
                     from firefly_user where username=?
                     """)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new JdbcException("failed to find Admin user", e);
        }
    }

    @Override
    public List<AdminUser> list() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select username, password_hash, roles, enabled, version, created_at, updated_at
                     from firefly_user order by username
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            List<AdminUser> users = new ArrayList<>();
            while (resultSet.next()) users.add(map(resultSet));
            return List.copyOf(users);
        } catch (SQLException e) {
            throw new JdbcException("failed to list Admin users", e);
        }
    }

    @Override
    public boolean create(AdminUser user) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into firefly_user
                     (username, password_hash, roles, enabled, version, created_at, updated_at)
                     values (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bind(statement, user);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) return false;
            throw new JdbcException("failed to create Admin user", e);
        }
    }

    @Override
    public boolean update(AdminUser user, long expectedVersion) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     update firefly_user
                     set password_hash=?, roles=?, enabled=?, version=?, updated_at=?
                     where username=? and version=?
                     """)) {
            statement.setString(1, user.passwordHash());
            statement.setString(2, encodeRoles(user.roles()));
            statement.setBoolean(3, user.enabled());
            statement.setLong(4, user.version());
            statement.setTimestamp(5, Timestamp.from(user.updatedAt()));
            statement.setString(6, user.username());
            statement.setLong(7, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new JdbcException("failed to update Admin user", e);
        }
    }

    @Override
    public boolean delete(String username, long expectedVersion) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "delete from firefly_user where username=? and version=?")) {
            statement.setString(1, username);
            statement.setLong(2, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new JdbcException("failed to delete Admin user", e);
        }
    }

    private void bind(PreparedStatement statement, AdminUser user) throws SQLException {
        statement.setString(1, user.username());
        statement.setString(2, user.passwordHash());
        statement.setString(3, encodeRoles(user.roles()));
        statement.setBoolean(4, user.enabled());
        statement.setLong(5, user.version());
        statement.setTimestamp(6, Timestamp.from(user.createdAt()));
        statement.setTimestamp(7, Timestamp.from(user.updatedAt()));
    }

    private AdminUser map(ResultSet resultSet) throws SQLException {
        return new AdminUser(
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                decodeRoles(resultSet.getString("roles")),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private String encodeRoles(Set<FireflyRole> roles) {
        return roles.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private Set<FireflyRole> decodeRoles(String roles) {
        return Arrays.stream(roles.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .map(FireflyRole::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
