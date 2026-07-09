package com.firefly.spring;

import com.firefly.integration.FireflyOptions;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Clock;

/**
 * Spring Boot properties for the thin Firefly starter layer.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "firefly")
public class FireflyProperties {
    private boolean enabled = true;
    private boolean autoStart = true;
    private int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
    private String workerThreadNamePrefix = "firefly-worker";

    /**
     * Converts Spring-bound properties into container-neutral embedded options.
     */
    FireflyOptions toOptions(Clock clock) {
        return FireflyOptions.builder()
                .clock(clock)
                .workerThreads(workerThreads)
                .workerThreadNamePrefix(workerThreadNamePrefix)
                .build();
    }
}
