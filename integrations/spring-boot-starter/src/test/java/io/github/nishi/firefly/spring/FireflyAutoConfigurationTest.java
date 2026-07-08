package io.github.nishi.firefly.spring;

import io.github.nishi.firefly.domain.CronSchedule;
import io.github.nishi.firefly.domain.JobDefinition;
import io.github.nishi.firefly.integration.FireflyJobRegistration;
import io.github.nishi.firefly.integration.FireflyScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireflyAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FireflyAutoConfiguration.class));

    @Test
    void createsSchedulerWhenEnabled() {
        contextRunner
                .withPropertyValues("firefly.auto-start=false", "firefly.worker-threads=1")
                .withUserConfiguration(JobConfiguration.class)
                .run(context -> {
                    FireflyScheduler scheduler = context.getBean(FireflyScheduler.class);
                    assertNotNull(scheduler);
                    assertTrue(scheduler.repository().find("spring-demo").isPresent());
                });
    }

    @Test
    void backsOffWhenDisabled() {
        contextRunner
                .withPropertyValues("firefly.enabled=false")
                .run(context -> assertFalse(context.containsBean("fireflyScheduler")));
    }

    @Configuration(proxyBeanMethods = false)
    static class JobConfiguration {
        @Bean
        FireflyJobRegistration springDemoJob() {
            JobDefinition definition = JobDefinition.builder()
                    .id("spring-demo")
                    .name("Spring Demo")
                    .handlerName("springDemoHandler")
                    .schedule(new CronSchedule("0/10 * * * * *"))
                    .zoneId(ZoneId.of("UTC"))
                    .build();
            return FireflyJobRegistration.of(definition, ignored -> {
            });
        }
    }
}
