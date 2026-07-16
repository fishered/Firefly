package com.firefly.server;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.MisfirePolicy;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.store.JobRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.logging.Logger;

final class DemoJobs {
    private static final Logger log = Logger.getLogger(DemoJobs.class.getName());

    private DemoJobs() {
    }

    static void register(JobHandlerRegistry registry, JobRepository repository) {
        registry.register("demoPrinter", context -> log.info(() ->
                "run job=" + context.jobId()
                        + ", scheduled=" + context.scheduledFireTime()
                        + ", actual=" + context.actualFireTime()
                        + ", params=" + context.parameters()));

        JobDefinition job = JobDefinition.builder()
                .id("demo-print-every-5s")
                .name("Demo print every five seconds")
                .handlerName("demoPrinter")
                .schedule(new CronSchedule("*/5 * * * * *"))
                .zoneId(ZoneId.of("Asia/Shanghai"))
                .misfirePolicy(MisfirePolicy.FIRE_ONCE)
                .misfireGrace(Duration.ofSeconds(2))
                .concurrencyPolicy(ConcurrencyPolicy.FORBID)
                .maxCatchUpCount(3)
                .timeout(Duration.ofSeconds(10))
                .parameters(Map.of("source", "app-demo"))
                .enabled(true)
                .build();

        repository.save(job, job.schedule().nextAfter(Instant.now(), job.zoneId()));
    }
}
