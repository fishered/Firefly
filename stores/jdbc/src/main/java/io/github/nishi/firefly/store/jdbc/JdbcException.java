package io.github.nishi.firefly.store.jdbc;

/**
 * Wraps SQL failures so core interfaces can stay free of checked JDBC exceptions.
 */
public final class JdbcException extends RuntimeException {
    public JdbcException(String message, Throwable cause) {
        super(message, cause);
    }
}
