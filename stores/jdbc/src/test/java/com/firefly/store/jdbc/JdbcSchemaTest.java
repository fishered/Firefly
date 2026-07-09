package com.firefly.store.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }

    @Test
    void initializesH2SchemaMoreThanOnce() {
        DataSource dataSource = rawH2DataSource();

        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
    }

    @Test
    void loadsDialectScripts() {
        assertEquals(4, JdbcSchemaScript.load(JdbcDialect.H2).size());
        assertEquals(4, JdbcSchemaScript.load(JdbcDialect.POSTGRESQL).size());
        assertEquals(3, JdbcSchemaScript.load(JdbcDialect.MYSQL).size());
    }

    private DataSource rawH2DataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
