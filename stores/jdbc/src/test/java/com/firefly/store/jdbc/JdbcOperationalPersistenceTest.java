package com.firefly.store.jdbc;

import com.firefly.audit.AuditRecord;
import com.firefly.executor.ExecutorInstanceLocation;
import com.firefly.store.JobHistoryRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOperationalPersistenceTest {
    @Test
    void persistsAuditHistoryAndSharedExecutorLocations() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        Instant now = Instant.parse("2026-07-19T10:00:00Z");

        JdbcAuditRepository audit = new JdbcAuditRepository(dataSource);
        audit.append(new AuditRecord(
                "audit-1", now, "ops", "ADMIN", "PATCH /api/jobs/job-1",
                "jobs", "job-1", "SUCCESS", "before", "after", "status=200"
        ));
        assertEquals("ops", audit.listRecent(10).getFirst().actor());

        JdbcJobHistoryRepository history = new JdbcJobHistoryRepository(dataSource);
        history.append(new JobHistoryRecord(
                "history-1", "job-1", 2, "SET_ENABLED", "ops",
                "before", "after", now
        ));
        assertEquals("SET_ENABLED", history.listByJob("job-1", 10).getFirst().action());

        JdbcExecutorInstanceDirectory directory = new JdbcExecutorInstanceDirectory(dataSource);
        directory.register(new ExecutorInstanceLocation(
                "orders", "instance-1", "gateway-a", "http://gateway-a:9801", "session-1",
                now, now.plusSeconds(35), Map.of("serviceName", "orders-service")
        ));
        assertEquals("gateway-a", directory.listOnline("orders", now).getFirst().gatewayNodeId());
        assertTrue(directory.heartbeat(
                "orders", "instance-1", "gateway-a", "session-1",
                now.plusSeconds(10), Duration.ofSeconds(35)
        ));
        assertTrue(directory.markOffline("orders", "instance-1", "gateway-a", "session-1"));
        assertTrue(directory.listOnline("orders", now.plusSeconds(11)).isEmpty());
    }
}
