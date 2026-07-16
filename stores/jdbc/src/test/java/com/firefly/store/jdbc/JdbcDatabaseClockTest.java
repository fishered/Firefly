package com.firefly.store.jdbc;

import com.firefly.metrics.SchedulerMetrics;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDatabaseClockTest {
    @Test
    void calibratesJvmClockAgainstDatabaseTimeAndExposesDrift() throws Exception {
        var dataSource = JdbcTestSupport.dataSource();
        Instant databaseNow;
        try (Connection connection = dataSource.getConnection();
             ResultSet resultSet = connection.createStatement().executeQuery("select current_timestamp")) {
            resultSet.next();
            databaseNow = resultSet.getTimestamp(1).toInstant();
        }
        SchedulerMetrics metrics = new SchedulerMetrics();
        Clock slowJvm = Clock.fixed(databaseNow.minusSeconds(5), ZoneOffset.UTC);

        try (JdbcDatabaseClock clock = new JdbcDatabaseClock(
                dataSource, slowJvm,
                new JdbcClockOptions(Duration.ofHours(1), Duration.ofMillis(100)), metrics
        )) {
            assertTrue(Math.abs(Duration.between(databaseNow, clock.instant()).toMillis()) < 1_000);
            assertTrue(metrics.snapshot().clockOffsetMillis() >= 4_000);
            assertTrue(metrics.snapshot().clockDriftWarnings() >= 1);
        }
    }
}
