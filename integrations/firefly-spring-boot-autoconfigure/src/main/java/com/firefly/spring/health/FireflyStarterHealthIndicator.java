package com.firefly.spring.health;

import com.firefly.executor.netty.NettyExecutorClient;
import com.firefly.spring.job.FireflyJobRegistrationProperties;
import com.firefly.spring.netty.FireflyNettyExecutorProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FireflyStarterHealthIndicator implements HealthIndicator {
    private final NettyExecutorClient executorClient;
    private final FireflyNettyExecutorProperties executorProperties;
    private final FireflyJobRegistrationProperties jobRegistrationProperties;
    private final FireflyStarterHealthState healthState;

    FireflyStarterHealthIndicator(
            NettyExecutorClient executorClient,
            FireflyNettyExecutorProperties executorProperties,
            FireflyJobRegistrationProperties jobRegistrationProperties,
            FireflyStarterHealthState healthState
    ) {
        this.executorClient = Objects.requireNonNull(executorClient, "executorClient");
        this.executorProperties = Objects.requireNonNull(executorProperties, "executorProperties");
        this.jobRegistrationProperties = Objects.requireNonNull(
                jobRegistrationProperties,
                "jobRegistrationProperties"
        );
        this.healthState = Objects.requireNonNull(healthState, "healthState");
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        Map<String, String> registrationFailures = executorClient.registrationFailures();
        int connectedGatewayCount = executorClient.connectedGatewayCount();
        FireflyStarterHealthState.JobRegistration jobRegistration = healthState.jobRegistration();

        details.put("executorName", executorProperties.getName());
        details.put("autoStart", executorProperties.isAutoStart());
        details.put("connectedGatewayCount", connectedGatewayCount);
        if (!registrationFailures.isEmpty()) {
            details.put("registrationFailures", registrationFailures);
            issues.add("executor registration rejected");
        }
        if (executorProperties.isAutoStart() && connectedGatewayCount == 0) {
            issues.add("no registered gateway connections");
        }

        details.put("jobRegistration", jobRegistrationDetails(jobRegistration));
        if (jobRegistrationProperties.isEnabled()
                && jobRegistration.status() == FireflyStarterHealthState.JobRegistrationStatus.FAILED) {
            issues.add("job registration failed");
        }

        if (issues.isEmpty()) {
            return Health.up().withDetails(details).build();
        }
        details.put("issues", List.copyOf(issues));
        return Health.down().withDetails(details).build();
    }

    private Map<String, Object> jobRegistrationDetails(FireflyStarterHealthState.JobRegistration jobRegistration) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", jobRegistrationProperties.isEnabled());
        details.put("status", jobRegistration.status().name().toLowerCase(java.util.Locale.ROOT));
        details.put("synchronizedJobs", jobRegistration.synchronizedJobs());
        details.put("failedJobs", jobRegistration.failedJobs());
        if (jobRegistration.checkedAt() != null) {
            details.put("checkedAt", jobRegistration.checkedAt().toString());
        }
        if (!jobRegistration.failures().isEmpty()) {
            details.put("failures", jobRegistration.failures());
        }
        return details;
    }
}
