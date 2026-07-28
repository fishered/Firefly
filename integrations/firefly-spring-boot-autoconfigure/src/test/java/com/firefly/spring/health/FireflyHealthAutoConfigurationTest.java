package com.firefly.spring.health;

import com.firefly.spring.netty.FireflyNettyExecutorAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireflyHealthAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FireflyNettyExecutorAutoConfiguration.class,
                    FireflyHealthAutoConfiguration.class
            ));

    @Test
    void createsHealthIndicatorWhenExecutorIsConfigured() {
        contextRunner
                .withPropertyValues(
                        "firefly.executor.name=billing-executor",
                        "firefly.executor.auto-start=false"
                )
                .run(context -> {
                    assertTrue(context.containsBean("fireflyStarterHealthState"));
                    assertTrue(context.containsBean("fireflyHealthIndicator"));
                });
    }

    @Test
    void doesNotCreateHealthIndicatorWithoutExecutor() {
        contextRunner.run(context -> assertFalse(context.containsBean("fireflyHealthIndicator")));
    }
}
