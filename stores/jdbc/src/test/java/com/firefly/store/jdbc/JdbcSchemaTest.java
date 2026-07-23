package com.firefly.store.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcSchemaTest {
    @Test
    void initializesH2SchemaByAutoDetection() throws Exception {
        DataSource dataSource = rawH2DataSource();

        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getTables(null, null, "FIREFLY_JOB", null)) {
            assertFalse(resultSet.next());
        }

        JdbcSchema.initialize(dataSource);

        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getTables(null, null, "FIREFLY_JOB", null)) {
            assertTrue(resultSet.next());
        }
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getTables(
                     null, null, "FIREFLY_INTEGRATION_KEY", null)) {
            assertTrue(resultSet.next());
        }
    }

    @Test
    void initializesH2SchemaMoreThanOnce() {
        DataSource dataSource = rawH2DataSource();

        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
    }

    @Test
    void initializesOnlyWhenSchemaIsEmpty() {
        DataSource dataSource = rawH2DataSource();

        JdbcSchema.initializeIfEmpty(dataSource, JdbcSchemaOptions.of("h2"));
        JdbcSchema.initializeIfEmpty(dataSource, JdbcSchemaOptions.of("h2"));
    }

    @Test
    void initializesConfiguredShardCountAndRejectsMismatchedClusterNodes() throws Exception {
        DataSource dataSource = rawH2DataSource();
        JdbcSchemaOptions sixtyFourShards = JdbcSchemaOptions.of("h2").withSchedulerShardCount(64);
        JdbcSchema.initialize(dataSource, sixtyFourShards);

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     select metadata_value from firefly_cluster_metadata
                     where metadata_key='scheduler.shard-count'
                     """); ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
            assertEquals("64", resultSet.getString(1));
        }

        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, 64);
        var job = com.firefly.domain.JobDefinition.builder()
                .id("configured-shard-job").name("Configured shard job").handlerName("handler")
                .schedule(new com.firefly.domain.CronSchedule("0 * * * * *")).build();
        jobs.save(job, java.time.Instant.parse("2026-07-15T10:00:00Z"));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "select shard_id from firefly_job where job_id=?")) {
            statement.setString(1, job.id());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(com.firefly.cluster.ShardHasher.shardFor(job.id(), 64), resultSet.getInt(1));
            }
        }

        JdbcException mismatch = assertThrows(
                JdbcException.class,
                () -> JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2").withSchedulerShardCount(32))
        );
        assertTrue(mismatch.getMessage().contains("immutable"));
    }

    @Test
    void reshardsOnlyWithExplicitConfirmationAndRecomputesJobs() throws Exception {
        DataSource dataSource = rawH2DataSource();
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        JdbcJobRepository jobs = new JdbcJobRepository(dataSource, 32);
        var job = com.firefly.domain.JobDefinition.builder()
                .id("reshard-job").name("Reshard job").handlerName("handler")
                .schedule(new com.firefly.domain.CronSchedule("0 * * * * *")).build();
        jobs.save(job, java.time.Instant.parse("2026-07-15T10:00:00Z"));

        IllegalArgumentException unconfirmed = assertThrows(
                IllegalArgumentException.class,
                () -> JdbcReshardTool.reshard(
                        dataSource, JdbcSchemaOptions.of("h2").withSchedulerShardCount(64), false
                )
        );
        assertTrue(unconfirmed.getMessage().contains("confirmation"));

        JdbcReshardTool.reshard(
                dataSource, JdbcSchemaOptions.of("h2").withSchedulerShardCount(64), true
        );
        JdbcSchema.validate(dataSource, JdbcSchemaOptions.of("h2").withSchedulerShardCount(64));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select shard_id from firefly_job where job_id=?")) {
            statement.setString(1, job.id());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(com.firefly.cluster.ShardHasher.shardFor(job.id(), 64), resultSet.getInt(1));
            }
        }
    }

    @Test
    void reshardRejectsActiveExecutions() throws Exception {
        DataSource dataSource = rawH2DataSource();
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    insert into firefly_execution (
                        execution_id, root_execution_id, run_attempt, retry_scheduled, job_id,
                        scheduled_fire_time, dispatch_time, dispatch_mode, completion_policy, status,
                        expected_targets, accepted_targets, owner_node_id, fencing_token, timeout_at,
                        created_at, updated_at
                    ) values (
                        'active-execution', 'active-execution', 1, false, 'job-1',
                        current_timestamp, current_timestamp, 'UNICAST', 'ALL_SUCCESS', 'RUNNING',
                        1, 1, 'node-1', 1, null, current_timestamp, current_timestamp
                    )
                    """);
        }

        JdbcException exception = assertThrows(
                JdbcException.class,
                () -> JdbcReshardTool.reshard(
                        dataSource, JdbcSchemaOptions.of("h2").withSchedulerShardCount(64), true
                )
        );

        assertTrue(exception.getMessage().contains("active executions"));
    }

    @Test
    void reshardRejectsActiveOutboxRows() throws Exception {
        DataSource dataSource = rawH2DataSource();
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    insert into firefly_dispatch_outbox (
                        outbox_id, execution_id, root_execution_id, run_attempt, job_id,
                        scheduled_fire_time, dispatch_time, status, attempt, available_at,
                        claim_owner, claim_until, ack_deadline, owner_node_id, fencing_token,
                        dispatch_type, snapshot_payload, last_error, created_at, updated_at
                    ) values (
                        'outbox-1', 'outbox-execution', 'outbox-execution', 1, 'job-1',
                        current_timestamp, current_timestamp, 'PENDING', 0, current_timestamp,
                        null, null, null, 'node-1', 1, 'REMOTE', '{}', '',
                        current_timestamp, current_timestamp
                    )
                    """);
        }

        JdbcException exception = assertThrows(
                JdbcException.class,
                () -> JdbcReshardTool.reshard(
                        dataSource, JdbcSchemaOptions.of("h2").withSchedulerShardCount(64), true
                )
        );

        assertTrue(exception.getMessage().contains("active dispatch outbox"));
    }

    @Test
    void upgradesVersionOneJobTableWithDispatchColumns() throws Exception {
        DataSource dataSource = rawH2DataSource();
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("alter table firefly_job drop column dispatch_mode");
            statement.execute("alter table firefly_job drop column routing_strategy");
            statement.execute("alter table firefly_job drop column completion_policy");
            statement.execute("alter table firefly_job drop column shard_count");
            statement.execute("alter table firefly_job drop column routing_key");
            statement.execute("delete from firefly_schema_version where version = 2");
        }

        JdbcSchema.initializeIfEmpty(dataSource, JdbcSchemaOptions.of("h2"));

        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getColumns(null, null, "FIREFLY_JOB", "DISPATCH_MODE")) {
            assertTrue(resultSet.next());
        }
    }

    @Test
    void upgradesVersionFourOutboxWithRoutingAndSnapshotColumns() throws Exception {
        DataSource dataSource = rawH2DataSource();
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("drop index idx_firefly_outbox_role_claim");
            statement.execute("alter table firefly_dispatch_outbox drop column dispatch_type");
            statement.execute("alter table firefly_dispatch_outbox drop column snapshot_payload");
            statement.execute("delete from firefly_schema_version where version = 7");
        }

        JdbcSchema.initializeIfEmpty(dataSource, JdbcSchemaOptions.of("h2"));

        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.createStatement().executeQuery(
                     "select version from firefly_schema_version where version=7")) {
            assertTrue(resultSet.next());
        }
    }

    @Test
    void upgradesVersionSevenExecutionRowsWithImmutableTimeoutDeadlines() throws Exception {
        DataSource dataSource = rawH2DataSource();
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("drop index idx_firefly_execution_timeout");
            statement.execute("alter table firefly_execution drop column timeout_at");
            statement.execute("delete from firefly_schema_version where version = 8");
        }

        JdbcSchema.initializeIfEmpty(dataSource, JdbcSchemaOptions.of("h2"));

        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.getMetaData().getColumns(
                     null, null, "FIREFLY_EXECUTION", "TIMEOUT_AT")) {
            assertTrue(resultSet.next());
        }
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.createStatement().executeQuery(
                     "select version from firefly_schema_version where version=8")) {
            assertTrue(resultSet.next());
        }
    }

    @Test
    void validatesInitializedSchema() {
        DataSource dataSource = rawH2DataSource();

        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));

        JdbcSchema.validate(dataSource, JdbcSchemaOptions.of("h2"));
    }

    @Test
    void rejectsMissingSchemaOnValidate() {
        DataSource dataSource = rawH2DataSource();

        assertThrows(JdbcException.class, () -> JdbcSchema.validate(dataSource, JdbcSchemaOptions.of("h2")));
    }

    @Test
    void loadsDialectScripts() {
        assertEquals(39, JdbcSchemaScript.load(JdbcDialect.H2).size());
        assertEquals(39, JdbcSchemaScript.load(JdbcDialect.POSTGRESQL).size());
        assertEquals(28, JdbcSchemaScript.load(JdbcDialect.MYSQL).size());
    }

    private DataSource rawH2DataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
