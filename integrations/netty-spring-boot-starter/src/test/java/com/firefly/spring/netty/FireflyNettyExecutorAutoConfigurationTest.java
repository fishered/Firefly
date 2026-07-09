package com.firefly.spring.netty;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.executor.netty.NettyJobHandlerRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireflyNettyExecutorAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FireflyNettyExecutorAutoConfiguration.class));

    @Test
    void doesNotStartUnlessExplicitlyEnabled() {
        contextRunner.run(context -> assertFalse(context.containsBean("fireflyNettyExecutorClient")));
    }

    @Test
    void createsClientWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "firefly.executor.netty.enabled=true",
                        "firefly.executor.netty.auto-start=false",
                        "firefly.executor.netty.executor-name=billing-executor"
                )
                .withUserConfiguration(HandlerConfiguration.class)
                .run(context -> assertTrue(context.getBean(NettyExecutorClient.class)
                        .handlerRegistry()
                        .find("billingHandler")
                        .isPresent()));
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfiguration {
        @Bean
        NettyJobHandlerRegistration billingHandler() {
            return NettyJobHandlerRegistration.of("billingHandler", ignored -> {
            });
        }
    }
}
