package com.firefly.spring;

import com.firefly.integration.FireflyJobRegistration;
import com.firefly.integration.FireflyScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Auto-configures Firefly for Spring Boot while keeping the scheduler core Spring-free.
 */
@AutoConfiguration
@ConditionalOnClass(FireflyScheduler.class)
@ConditionalOnProperty(prefix = "firefly", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FireflyProperties.class)
public class FireflyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public Clock fireflyClock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public FireflyScheduler fireflyScheduler(
            FireflyProperties properties,
            Clock fireflyClock,
            ObjectProvider<FireflyJobRegistration> registrations
    ) {
        FireflyScheduler scheduler = FireflyScheduler.create(properties.toOptions(fireflyClock));
        registrations.orderedStream().forEach(scheduler::register);
        if (properties.isAutoStart()) {
            scheduler.start();
        }
        return scheduler;
    }
}
