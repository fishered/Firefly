package com.firefly.store.jdbc;

import com.firefly.cluster.SchedulerShardConfig;

import java.util.Locale;
import java.util.Objects;

/**
 * Controls schema initialization without binding callers to a concrete database.
 */
public record JdbcSchemaOptions(String dialect, int schedulerShardCount) {
    public static final String AUTO = "auto";

    public JdbcSchemaOptions {
        Objects.requireNonNull(dialect, "dialect");
        dialect = dialect.trim().toLowerCase(Locale.ROOT);
        if (dialect.isBlank()) {
            throw new IllegalArgumentException("dialect must not be blank");
        }
        schedulerShardCount = new SchedulerShardConfig(schedulerShardCount).shardCount();
    }

    public static JdbcSchemaOptions auto() {
        return new JdbcSchemaOptions(AUTO, SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    public static JdbcSchemaOptions of(String dialect) {
        return new JdbcSchemaOptions(dialect, SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    public JdbcSchemaOptions withSchedulerShardCount(int shardCount) {
        return new JdbcSchemaOptions(dialect, shardCount);
    }

    public boolean autoDetect() {
        return AUTO.equals(dialect);
    }
}
