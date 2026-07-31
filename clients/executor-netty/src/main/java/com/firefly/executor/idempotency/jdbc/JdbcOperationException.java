package com.firefly.executor.idempotency.jdbc;

/** Consistent unchecked boundary for executor-side JDBC failures. */
final class JdbcOperationException extends IllegalStateException {
    JdbcOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
