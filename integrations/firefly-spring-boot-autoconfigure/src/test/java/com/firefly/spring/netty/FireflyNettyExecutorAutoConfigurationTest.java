package com.firefly.spring.netty;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.handler.FireflyJobHandlerRegistration;
import com.firefly.handler.NamedJobHandler;
import com.firefly.spring.annotation.FireflyJob;
import com.firefly.spring.job.FireflyJobRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FireflyNettyExecutorAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FireflyNettyExecutorAutoConfiguration.class));

    @Test
    void doesNotStartUntilExecutorNameIsConfigured() {
        contextRunner.run(context -> assertFalse(context.containsBean("fireflyExecutorClient")));
    }

    @Test
    void createsClientFromMinimalConfiguration() {
        contextRunner
                .withPropertyValues(
                        "firefly.executor.name=billing-executor",
                        "firefly.executor.auto-start=false",
                        "spring.application.name=billing-service"
                )
                .withUserConfiguration(HandlerConfiguration.class)
                .run(context -> {
                    NettyExecutorClient client = context.getBean(NettyExecutorClient.class);
                    assertTrue(client.handlerRegistry().find("billingHandler").isPresent());
                    assertTrue(client.handlerRegistry().find("inventoryHandler").isPresent());
                });
    }

    @Test
    void createsJdbcBusinessIdempotencyStoreWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "firefly.executor.name=billing-executor",
                        "firefly.executor.auto-start=false",
                        "firefly.executor.business-idempotency.enabled=true",
                        "firefly.executor.business-idempotency.abandoned-claim-timeout=PT45M"
                )
                .withUserConfiguration(DataSourceConfiguration.class)
                .run(context -> assertInstanceOf(
                        com.firefly.executor.idempotency.jdbc.JdbcBusinessIdempotencyStore.class,
                        context.getBean(com.firefly.idempotency.BusinessIdempotencyStore.class)
                ));
    }

    @Test
    void discoversAnnotatedHandlerAndMultipleJobDeclarations() {
        contextRunner
                .withPropertyValues(
                        "firefly.executor.name=billing-executor",
                        "firefly.executor.auto-start=false"
                )
                .withUserConfiguration(AnnotatedJobConfiguration.class)
                .run(context -> {
                    NettyExecutorClient client = context.getBean(NettyExecutorClient.class);
                    FireflyJobRegistrar registrar = context.getBean(FireflyJobRegistrar.class);
                    String handlerName = AnnotatedBillingJobs.class.getName() + "#bill";

                    assertTrue(client.handlerRegistry().find(handlerName).isPresent());
                    assertEquals(2, registrar.registrationCount());

                    client.handlerRegistry().find(handlerName).orElseThrow().handle(
                            new com.firefly.domain.ExecutionContext(
                                    "execution-1", handlerName + ":daily", handlerName,
                                    Instant.now(), Instant.now(), Instant.now(), Map.of()
                            )
                    );
                    assertEquals(1, context.getBean(AnnotatedBillingJobs.class).invocations.get());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class HandlerConfiguration {
        @Bean
        FireflyJobHandlerRegistration billingHandler() {
            return FireflyJobHandlerRegistration.of("billingHandler", ignored -> {
            });
        }

        @Bean
        NamedJobHandler inventoryHandler() {
            return new NamedJobHandler() {
                @Override
                public String handlerName() {
                    return "inventoryHandler";
                }

                @Override
                public void handle(com.firefly.domain.ExecutionContext context) {
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfiguration {
        @Bean
        javax.sql.DataSource dataSource() {
            org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:starter-idempotency;DB_CLOSE_DELAY=-1");
            dataSource.setUser("sa");
            return dataSource;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AnnotatedJobConfiguration {
        @Bean
        AnnotatedBillingJobs annotatedBillingJobs() {
            return new AnnotatedBillingJobs();
        }
    }

    static class AnnotatedBillingJobs {
        private final AtomicInteger invocations = new AtomicInteger();

        @FireflyJob(
                key = "daily",
                cron = "0 0 2 * * *",
                zoneId = "Asia/Shanghai"
        )
        @FireflyJob(
                key = "reconciliation",
                cron = "0 0 3 * * *",
                zoneId = "Asia/Shanghai"
        )
        public void bill(com.firefly.domain.ExecutionContext executionContext) {
            invocations.incrementAndGet();
        }
    }
}
