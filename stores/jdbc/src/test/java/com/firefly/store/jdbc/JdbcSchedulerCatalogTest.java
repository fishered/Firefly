package com.firefly.store.jdbc;

import com.firefly.domain.ExecutorDefinition;
import com.firefly.domain.ExecutorProtocol;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.JobGroupDefinition;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSchedulerCatalogTest {
    @Test
    void persistsLogicalExecutorDefinitionsSeparatelyFromRuntimeInstances() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcSchedulerCatalog catalog = new JdbcSchedulerCatalog(dataSource);
        catalog.saveExecutor(ExecutorDefinition.builder()
                .name("billing-executor")
                .description("Billing service capability")
                .protocols(Set.of(ExecutorProtocol.TCP, ExecutorProtocol.HTTP))
                .metadata(Map.of("owner", "billing"))
                .build());

        ExecutorDefinition executor = catalog.findExecutor("billing-executor").orElseThrow();
        assertTrue(executor.protocols().contains(ExecutorProtocol.TCP));
        assertEquals("billing", executor.metadata().get("owner"));
        assertEquals("billing-executor", catalog.listExecutors().getFirst().name());
    }

    @Test
    void persistsJobGroupsAndCatalogJobs() {
        DataSource dataSource = JdbcTestSupport.dataSource();
        JdbcSchedulerCatalog catalog = new JdbcSchedulerCatalog(
                dataSource, ignored -> java.time.Instant.parse("2026-07-15T10:00:00Z")
        );
        catalog.saveJobGroup(JobGroupDefinition.builder()
                .id("billing").name("Billing").executorName("billing-executor")
                .metadata(Map.of("owner", "finance")).build());
        catalog.saveJob(JobDefinition.builder()
                .id("billing-daily").groupId("billing").name("Billing daily")
                .handlerName("run").schedule(new CronSchedule("0 0 1 * * *")).build());

        assertEquals("billing-executor", catalog.findJobGroup("billing").orElseThrow().executorName());
        assertEquals("billing-daily", catalog.findJob("billing-daily").orElseThrow().id());
        assertEquals(1, catalog.listJobsByGroup("billing").size());
    }

}
