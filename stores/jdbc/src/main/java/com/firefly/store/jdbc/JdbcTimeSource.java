package com.firefly.store.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

@FunctionalInterface
interface JdbcTimeSource {
    Instant now(Connection connection) throws SQLException;

    static JdbcTimeSource database() {
        return connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select current_timestamp")) {
                if (!resultSet.next()) {
                    throw new SQLException("database did not return current_timestamp");
                }
                return resultSet.getTimestamp(1).toInstant();
            }
        };
    }
}
