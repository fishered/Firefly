package io.github.nishi.firefly.server;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.github.nishi.firefly.domain.ConcurrencyPolicy;
import io.github.nishi.firefly.domain.CronSchedule;
import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.domain.MisfirePolicy;
import io.github.nishi.firefly.engine.SchedulerEngine;
import io.github.nishi.firefly.registry.JobHandlerRegistry;
import io.github.nishi.firefly.store.JobRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.logging.Logger;

public final class App {
    private static final Logger log = Logger.getLogger(App.class.getName());

    private App() {
    }

    public static void main(String[] args) throws InterruptedException {
        Injector injector = Guice.createInjector(new SchedulerModule());

        JobHandlerRegistry registry = injector.getInstance(JobHandlerRegistry.class);
        registry.register("demoPrinter", context -> log.info(() ->
                "run job=" + context.jobId()
                        + ", scheduled=" + context.scheduledFireTime()
                        + ", actual=" + context.actualFireTime()
                        + ", params=" + context.parameters()));

        JobRepository repository = injector.getInstance(JobRepository.class);
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

        SchedulerEngine engine = injector.getInstance(SchedulerEngine.class);
        engine.start();

        Runtime.getRuntime().addShutdownHook(new Thread(engine::stop, "firefly-shutdown"));
        Thread.currentThread().join();
    }
}

