package com.firefly.server;

import com.firefly.store.jdbc.JdbcSchema;
import com.firefly.store.jdbc.JdbcSchemaOptions;
import com.firefly.store.jdbc.JdbcReshardTool;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Initializes or upgrades the configured JDBC schema without starting Firefly runtime services. */
public final class SchemaTool {
    private SchemaTool() {
    }

    public static void main(String[] args) throws Exception {
        ServerOptions options = ServerOptions.parse(args);
        ServerStoreOptions store = options.store();
        if (!store.jdbcEnabled()) {
            throw new IllegalArgumentException("schema migration requires firefly.store.type=jdbc");
        }
        DataSource dataSource = new DriverManagerDataSource(
                store.jdbcUrl(), store.jdbcUsername(), store.jdbcPassword()
        );
        JdbcSchemaOptions schemaOptions = JdbcSchemaOptions.of(store.jdbcDialect())
                .withSchedulerShardCount(options.schedulerShards().shardCount());
        java.util.Map<String, String> flags = ServerFlagParser.parse(args);
        if ("reshard".equalsIgnoreCase(flags.get("firefly.schema.action"))) {
            boolean confirmed = reshardConfirmed(flags);
            JdbcReshardTool.ReshardResult result = JdbcReshardTool.reshard(dataSource, schemaOptions, confirmed);
            System.out.println("Firefly scheduler reshard completed: old-shard-count="
                    + result.oldShardCount()
                    + ", new-shard-count=" + result.newShardCount()
                    + ", affected-jobs=" + result.affectedJobs()
                    + ", deleted-leases=" + result.deletedLeases());
            return;
        }
        JdbcSchema.initialize(dataSource, schemaOptions);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            List<Integer> versions = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery(
                    "select version from firefly_schema_version order by version")) {
                while (resultSet.next()) versions.add(resultSet.getInt(1));
            }
            List<String> tables = new ArrayList<>();
            try (ResultSet resultSet = connection.getMetaData().getTables(null, null, "firefly_%", new String[]{"TABLE"})) {
                while (resultSet.next()) tables.add(resultSet.getString("TABLE_NAME"));
            }
            tables.sort(String.CASE_INSENSITIVE_ORDER);
            System.out.println("Firefly schema initialized: url=" + store.jdbcUrl());
            System.out.println("Schema versions: " + versions);
            System.out.println("Firefly tables: " + tables);
        }
    }

    private static boolean reshardConfirmed(java.util.Map<String, String> flags) {
        String explicit = flags.getOrDefault(
                "firefly.schema.reshard.confirm",
                System.getenv("FIREFLY_SCHEMA_RESHARD_CONFIRM")
        );
        if (Boolean.parseBoolean(explicit)) {
            return true;
        }
        return "RESHARD".equals(flags.getOrDefault(
                "firefly.schema.confirm", System.getenv("FIREFLY_SCHEMA_CONFIRM")
        ));
    }
}
