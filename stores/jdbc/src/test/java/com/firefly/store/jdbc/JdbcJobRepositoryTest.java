package com.firefly.store.jdbc;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.FixedRateSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.MisfirePolicy;
import com.firefly.store.DueJobBatch;
import com.firefly.store.ScheduledJobRecord;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcJobRepositoryTest {
    @Test
    void savesAndLoadsJobDefinitions() {
        JdbcJobRepository repository = repository();
        Instant nextFireTime = Instant.parse("2026-01-01T01:00:00Z");

        repository.save(reportJob("report", nextFireTime), nextFireTime);

        ScheduledJobRecord record = repository.find("report").orElseThrow();
        assertEquals("billing", record.definition().groupId());
        assertEquals("reportHandler", record.definition().handlerName());
        assertEquals(ZoneId.of("Asia/Shanghai"), record.definition().zoneId());
        assertEquals(Map.of("tenant", "firefly"), record.definition().parameters());
        assertEquals(nextFireTime, record.nextFireTime());
    }

    @Test
    void updatesNextFireTimeWithCompareAndSet() {
        JdbcJobRepository repository = repository();
        Instant first = Instant.parse("2026-01-01T01:00:00Z");
        Instant second = Instant.parse("2026-01-01T01:01:00Z");

        repository.save(reportJob("report", first), first);

        assertFalse(repository.updateNextFireTime("report", second, Instant.parse("2026-01-01T01:02:00Z")));
        assertTrue(repository.updateNextFireTime("report", first, second));
        assertEquals(second, repository.find("report").orElseThrow().nextFireTime());
    }

    @Test
    void keepsSameFireTimeBatchTogetherUntilHardLimit() {
        JdbcJobRepository repository = repository();
        Instant fireTime = Instant.parse("2026-01-01T01:00:00Z");

        repository.save(reportJob("a", fireTime), fireTime);
        repository.save(reportJob("b", fireTime), fireTime);
        repository.save(reportJob("c", fireTime), fireTime);

        DueJobBatch batch = repository.findDueBatch(fireTime, 1, 10);

        assertEquals(fireTime, batch.fireTime());
        assertEquals(3, batch.records().size());
        assertFalse(batch.truncated());
    }

    @Test
    void marksBatchTruncatedWhenHardLimitCutsSameFireTime() {
        JdbcJobRepository repository = repository();
        Instant fireTime = Instant.parse("2026-01-01T01:00:00Z");

        repository.save(reportJob("a", fireTime), fireTime);
        repository.save(reportJob("b", fireTime), fireTime);
        repository.save(reportJob("c", fireTime), fireTime);

        DueJobBatch batch = repository.findDueBatch(fireTime, 1, 2);

        assertEquals(2, batch.records().size());
        assertTrue(batch.truncated());
    }

    @Test
    void supportsFixedRateSchedulePersistence() {
        JdbcJobRepository repository = repository();
        Instant nextFireTime = Instant.parse("2026-01-01T01:00:00Z");
        JobDefinition job = JobDefinition.builder()
                .id("fixed")
                .name("Fixed")
                .handlerName("fixedHandler")
                .schedule(new FixedRateSchedule(Duration.ofMinutes(1)))
                .build();

        repository.save(job, nextFireTime);

        assertEquals(
                nextFireTime.plus(Duration.ofMinutes(1)),
                repository.find("fixed").orElseThrow().definition().schedule().nextAfter(nextFireTime, ZoneId.of("UTC"))
        );
    }

    private JdbcJobRepository repository() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        return new JdbcJobRepository(dataSource);
    }

    private JobDefinition reportJob(String id, Instant nextFireTime) {
        return JobDefinition.builder()
                .id(id)
                .groupId("billing")
                .name("Report " + id)
                .handlerName("reportHandler")
                .schedule(new CronSchedule("0 0 9 * * *"))
                .zoneId(ZoneId.of("Asia/Shanghai"))
                .misfirePolicy(MisfirePolicy.FIRE_ONCE)
                .misfireGrace(Duration.ofSeconds(3))
                .concurrencyPolicy(ConcurrencyPolicy.FORBID)
                .maxCatchUpCount(5)
                .timeout(Duration.ofSeconds(30))
                .parameters(Map.of("tenant", "firefly"))
                .enabled(true)
                .build();
    }
}
