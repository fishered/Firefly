package com.firefly.store.jdbc;

import com.firefly.execution.ExecutionLifecycleStore;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** JDBC lifecycle store whose cancel/timeout statements update execution, targets and outbox atomically. */
public final class JdbcExecutionLifecycleStore implements ExecutionLifecycleStore {
    private final JdbcExecutionRepository executions;

    public JdbcExecutionLifecycleStore(DataSource dataSource) {
        this.executions = new JdbcExecutionRepository(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public boolean cancel(String executionId, Instant cancelledAt, String reason) {
        return executions.cancelExecution(executionId, cancelledAt, reason);
    }

    @Override
    public List<String> expireTimeouts(Instant now, int limit) {
        return executions.expireTimedOutExecutions(now, limit);
    }
}
