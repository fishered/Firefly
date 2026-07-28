package com.firefly.compatibility;

import com.firefly.domain.ExecutionContext;
import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.spring.annotation.FireflyJob;
import com.firefly.spring.job.FireflyJobRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

class FireflySpringBootConsumerTest {
    @Test
    void starterAutoConfigurationLoadsWithManagedSpringBootVersion() {
        assertThat(SpringBootVersion.getVersion())
                .startsWith(System.getProperty("expected.boot.line"));

        try (var context = new SpringApplicationBuilder(ConsumerApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "firefly.executor.name=compat-executor",
                        "firefly.executor.auto-start=false",
                        "firefly.executor.job-registration.enabled=false"
                )
                .run()) {
            NettyExecutorClient client = context.getBean(NettyExecutorClient.class);
            String handlerName = ConsumerJobs.class.getName() + "#invoice";

            assertThat(client.handlerRegistry().find(handlerName)).isPresent();
            assertThat(context.getBean(FireflyJobRegistrar.class).registrationCount()).isEqualTo(1);
        }
    }

    @SpringBootApplication
    static class ConsumerApplication {
        public static void main(String[] args) {
            SpringApplication.run(ConsumerApplication.class, args);
        }

        @Bean
        ConsumerJobs consumerJobs() {
            return new ConsumerJobs();
        }
    }

    static class ConsumerJobs {
        @FireflyJob(key = "daily", cron = "0 0 2 * * *")
        void invoice(ExecutionContext context) {
        }
    }
}
