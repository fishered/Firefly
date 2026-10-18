package com.firefly.store.jdbc;

import com.firefly.audit.AuditQuery;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcCursorScaleTest {
    @Test
    @Tag("production-readiness")
    void pagesOneHundredThousandRowsWithBoundedKeysetQueries() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        Instant occurredAt = Instant.parse("2026-10-18T00:00:00Z");

        try (var connection = dataSource.getConnection();
             var insert = connection.prepareStatement("""
                     insert into firefly_audit_log (
                       audit_id, occurred_at, actor, role_name, action_name, resource_type,
                       resource_id, outcome, before_payload, after_payload, detail
                     ) values (?, ?, 'scale-operator', 'ADMIN', 'QUERY', 'jobs', ?, 'SUCCESS', '', '', '')
                     """)) {
            connection.setAutoCommit(false);
            for (int index = 0; index < 100_000; index++) {
                String id = "audit-" + String.format(java.util.Locale.ROOT, "%06d", index);
                insert.setString(1, id);
                insert.setTimestamp(2, Timestamp.from(occurredAt));
                insert.setString(3, "job-" + index);
                insert.addBatch();
                if ((index + 1) % 1_000 == 0) insert.executeBatch();
            }
            connection.commit();
        }

        JdbcAuditRepository repository = new JdbcAuditRepository(dataSource);
        var first = repository.query(new AuditQuery(
                "scale-operator", "QUERY", "jobs", "SUCCESS", null, null, null, 100
        ));
        assertEquals(100, first.items().size());
        assertNotNull(first.nextCursor());
        assertEquals("audit-099999", first.items().getFirst().auditId());
        assertEquals("audit-099900", first.items().getLast().auditId());

        var second = repository.query(new AuditQuery(
                "scale-operator", "QUERY", "jobs", "SUCCESS", null, null, first.nextCursor(), 100
        ));
        assertEquals(100, second.items().size());
        assertEquals("audit-099899", second.items().getFirst().auditId());
        assertEquals("audit-099800", second.items().getLast().auditId());
        var firstIds = new HashSet<>(first.items().stream().map(record -> record.auditId()).toList());
        assertFalse(second.items().stream().anyMatch(record -> firstIds.contains(record.auditId())));
        assertTrue(second.hasMore());
    }
}
