package com.firefly.example.embedded;

import com.firefly.domain.ConcurrencyPolicy;
import com.firefly.domain.CronSchedule;
import com.firefly.domain.JobDefinition;
import com.firefly.domain.MisfirePolicy;
import com.firefly.integration.FireflyJobRegistration;
import com.firefly.integration.FireflyOptions;
import com.firefly.integration.FireflyScheduler;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal traditional Java integration example that starts Firefly in-process.
 */
public final class EmbeddedBasicExample {
    private EmbeddedBasicExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        ExampleLogging.configure();
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger executions = new AtomicInteger();

        JobDefinition job = JobDefinition.builder()
                .id("example-embedded-every-2s")
                .name("Embedded example every two seconds")
                .handlerName("examplePrinter")
                .schedule(new CronSchedule("*/2 * * * * *"))
                .zoneId(ZoneId.of("Asia/Shanghai"))
                .misfirePolicy(MisfirePolicy.FIRE_ONCE)
                .misfireGrace(Duration.ofSeconds(2))
                .concurrencyPolicy(ConcurrencyPolicy.FORBID)
                .timeout(Duration.ofSeconds(10))
                .parameters(Map.of("source", "examples:embedded-basic"))
                .build();

        FireflyOptions options = FireflyOptions.builder()
                .workerThreads(2)
                .workerThreadNamePrefix("firefly-example-worker")
                .build();

        try (FireflyScheduler scheduler = FireflyScheduler.create(options)) {
            scheduler.register(FireflyJobRegistration.of(job, context -> {
                int count = executions.incrementAndGet();
                System.out.println("example job fired count=" + count
                        + ", job=" + context.jobId()
                        + ", scheduled=" + context.scheduledFireTime()
                        + ", actual=" + context.actualFireTime()
                        + ", params=" + context.parameters());
                latch.countDown();
            }));
            scheduler.start();

            boolean completed = latch.await(8, TimeUnit.SECONDS);
            System.out.println("embedded example completed=" + completed + ", executions=" + executions.get());
        }
    }
}
