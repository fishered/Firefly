package com.firefly.store.jdbc;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.trigger.BackfillItem;
import com.firefly.trigger.BackfillOperation;
import com.firefly.trigger.BackfillOptions;
import com.firefly.trigger.BackfillProgress;
import com.firefly.trigger.BackfillRequest;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcBackfillOperationStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void persistsCursorAndRecoversExpiredLeaseAcrossInstances() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcBackfillOperationStore first = new JdbcBackfillOperationStore(dataSource);
        JdbcBackfillOperationStore second = new JdbcBackfillOperationStore(dataSource);
        BackfillOperation operation = operation();
        List<BackfillItem> items = List.of(
                item(0, NOW, "root@0"), item(1, NOW.plusSeconds(60), "root@1")
        );

        assertTrue(first.create(operation, items));
        assertFalse(second.create(operation, items));
        BackfillOperation claimed = first.claim("run", NOW, "node-a", Duration.ofSeconds(10)).orElseThrow();
        assertTrue(second.claim("run", NOW.plusSeconds(5), "node-b", Duration.ofSeconds(10)).isEmpty());

        BackfillOperation reclaimed = second.claim(
                "run", NOW.plusSeconds(11), "node-b", Duration.ofSeconds(10)).orElseThrow();
        assertEquals(claimed.version() + 1, reclaimed.version());
        assertEquals("root@0", second.nextItem("run", "node-b").orElseThrow().executionId());

        BackfillOperation firstItem = second.recordItem(
                "run", "node-b", reclaimed.version(), BackfillItem.BackfillItemStatus.DISPATCHED,
                "", NOW.plusSeconds(11), NOW.plusSeconds(11)
        ).orElseThrow();
        assertEquals(1, firstItem.cursor());
        assertEquals(BackfillProgress.BackfillStatus.PAUSED, firstItem.status());
        assertTrue(firstItem.canaryActive());

        BackfillOperation promoted = first.promote("run", NOW.plusSeconds(12)).orElseThrow();
        assertEquals(BackfillProgress.BackfillStatus.RUNNING, promoted.status());
        assertFalse(promoted.canaryActive());
        BackfillOperation secondClaim = first.claim(
                "run", NOW.plusSeconds(12), "node-a", Duration.ofSeconds(10)).orElseThrow();
        BackfillOperation completed = first.recordItem(
                "run", "node-a", secondClaim.version(), BackfillItem.BackfillItemStatus.DISPATCHED,
                "", NOW.plusSeconds(12), NOW.plusSeconds(12)
        ).orElseThrow();
        assertEquals(BackfillProgress.BackfillStatus.COMPLETED, completed.status());
        assertEquals(2, completed.dispatched());
        assertEquals(2, new JdbcBackfillOperationStore(dataSource).find("run").orElseThrow().cursor());
    }

    private static BackfillOperation operation() {
        BackfillRequest request = new BackfillRequest(
                "run", "job", NOW, NOW.plusSeconds(60), 10, "root"
        );
        BackfillOptions options = new BackfillOptions(10, 0, 50, Set.of());
        JobDefinition definition = JobDefinition.builder().id("job").name("job").handlerName("handler")
                .schedule(new CronSchedule("0 * * * * *")).build();
        return new BackfillOperation(request, options, definition,
                BackfillProgress.BackfillStatus.PENDING, 2, 0, 0, 0, 1, true,
                NOW, "", null, 0, NOW, NOW);
    }

    private static BackfillItem item(int ordinal, Instant fireTime, String executionId) {
        return new BackfillItem(ordinal, fireTime, executionId,
                BackfillItem.BackfillItemStatus.PENDING, "");
    }
}
