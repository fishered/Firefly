package com.firefly.executor.idempotency.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Minimal transaction boundary shared by the executor-side JDBC store. */
final class JdbcTransactionTemplate {
    private final DataSource dataSource;

    JdbcTransactionTemplate(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    <T> T execute(String operation, Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                if (failure instanceof SQLException sqlFailure) {
                    throw new JdbcOperationException("failed to " + operation, sqlFailure);
                }
                throw failure;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (SQLException failure) {
            throw new JdbcOperationException("failed to " + operation, failure);
        }
    }

    private void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // Closing the connection remains the final cleanup boundary.
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T execute(Connection connection) throws SQLException;
    }
}
