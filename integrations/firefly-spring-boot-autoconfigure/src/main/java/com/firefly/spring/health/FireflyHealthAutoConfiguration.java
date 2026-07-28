package com.firefly.spring.health;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.spring.job.FireflyJobRegistrationProperties;
import com.firefly.spring.netty.FireflyNettyExecutorAutoConfiguration;
import com.firefly.spring.netty.FireflyNettyExecutorProperties;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureAfter(FireflyNettyExecutorAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean({NettyExecutorClient.class, FireflyStarterHealthState.class})
public class FireflyHealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "fireflyHealthIndicator")
    public HealthIndicator fireflyHealthIndicator(
            NettyExecutorClient executorClient,
            FireflyNettyExecutorProperties executorProperties,
            FireflyJobRegistrationProperties jobRegistrationProperties,
            FireflyStarterHealthState healthState
    ) {
        return new FireflyStarterHealthIndicator(
                executorClient,
                executorProperties,
                jobRegistrationProperties,
                healthState
        );
    }
}
