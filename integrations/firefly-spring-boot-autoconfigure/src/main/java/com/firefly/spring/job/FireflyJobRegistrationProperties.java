package com.firefly.spring.job;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration for startup synchronization of declared jobs. */
@Getter
@Setter
@ConfigurationProperties(prefix = "firefly.executor.job-registration")
public class FireflyJobRegistrationProperties {
    private boolean enabled = true;
    private String adminUrl = "http://127.0.0.1:9710";
    private String adminToken = "";
    private boolean updateExisting;
    private boolean failFast;
    private int maxAttempts = 3;
    private Duration retryDelay = Duration.ofSeconds(1);
    private Duration requestTimeout = Duration.ofSeconds(5);
}
