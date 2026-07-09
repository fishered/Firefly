package com.firefly.catalog;

import com.firefly.domain.CronSchedule;
import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobGroupDefinition;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySchedulerCatalogTest {
    @Test
    void storesExecutorsGroupsAndJobsSeparately() {
        InMemorySchedulerCatalog catalog = new InMemorySchedulerCatalog();
        catalog.saveExecutor(ExecutorDefinition.builder()
                .name("billing-executor")
                .build());
        catalog.saveJobGroup(JobGroupDefinition.builder()
                .id("billing")
                .name("Billing")
                .executorName("billing-executor")
                .build());
        catalog.saveJob(JobDefinition.builder()
                .id("billing-daily")
                .groupId("billing")
                .name("Billing Daily")
                .handlerName("billingHandler")
                .schedule(new CronSchedule("0 0 1 * * *"))
                .zoneId(ZoneId.of("Asia/Shanghai"))
                .build());

        assertTrue(catalog.findExecutor("billing-executor").isPresent());
        assertEquals("billing-executor", catalog.findJobGroup("billing").orElseThrow().executorName());
        assertEquals("billing-daily", catalog.listJobsByGroup("billing").getFirst().id());
    }
}
