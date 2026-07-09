package io.github.nishi.firefly.plugin.admin;

import io.github.nishi.firefly.domain.CronSchedule;
import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.store.ScheduledJobRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdminWebJsonTest {
    @Test
    void rendersJobsAsJson() {
        JobDefinition definition = JobDefinition.builder()
                .id("daily-report")
                .name("Daily Report")
                .handlerName("reportHandler")
                .schedule(new CronSchedule("0 0 9 * * *"))
                .zoneId(ZoneId.of("Asia/Shanghai"))
                .build();

        String json = AdminWebJson.jobs(List.of(new ScheduledJobRecord(
                definition,
                Instant.parse("2026-01-01T01:00:00Z")
        )));

        assertTrue(json.contains("\"id\":\"daily-report\""));
        assertTrue(json.contains("\"zoneId\":\"Asia/Shanghai\""));
    }
}
