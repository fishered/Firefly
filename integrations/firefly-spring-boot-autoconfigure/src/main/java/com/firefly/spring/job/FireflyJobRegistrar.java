package com.firefly.spring.job;

import com.firefly.executor.netty.NettyExecutorClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Synchronizes declared Spring jobs with the Firefly control plane after startup. */
public final class FireflyJobRegistrar implements ApplicationListener<ApplicationReadyEvent> {
    private static final Log log = LogFactory.getLog(FireflyJobRegistrar.class);
    private final String executorName;
    private final FireflyJobRegistrationProperties properties;
    private final CopyOnWriteArrayList<FireflyJobRegistration> registrations;
    private final NettyExecutorClient executorClient;
    private final HttpClient httpClient;

    public FireflyJobRegistrar(
            String executorName,
            FireflyJobRegistrationProperties properties,
            List<FireflyJobRegistration> registrations,
            NettyExecutorClient executorClient
    ) {
        this.executorName = requireNonBlank(executorName, "executorName");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registrations = new CopyOnWriteArrayList<>(registrations);
        this.executorClient = Objects.requireNonNull(executorClient, "executorClient");
        validateConfiguration();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(positive(properties.getRequestTimeout(), "requestTimeout"))
                .build();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.isEnabled() || registrations.isEmpty()) {
            return;
        }
        int synchronizedJobs = 0;
        List<String> failures = new ArrayList<>();
        for (FireflyJobRegistration registration : registrations) {
            try {
                synchronizeWithRetry(registration);
                synchronizedJobs++;
            } catch (RuntimeException e) {
                failures.add(registration.id() + ": " + e.getMessage());
                log.warn("Failed to synchronize Firefly job " + registration.id() + ": " + e.getMessage());
            }
        }
        if (failures.isEmpty()) {
            log.info("Firefly startup job registration completed: executor=" + executorName
                    + ", jobs=" + synchronizedJobs
                    + ", updateExisting=" + properties.isUpdateExisting());
            return;
        }
        if (properties.isFailFast()) {
            throw new IllegalStateException("Firefly startup job registration failed: " + String.join("; ", failures));
        }
        log.warn("Firefly startup job registration completed with failures: executor=" + executorName
                + ", synchronized=" + synchronizedJobs + ", failed=" + failures.size());
    }

    public void synchronizeJobs() {
        if (!properties.isEnabled()) {
            return;
        }
        for (FireflyJobRegistration registration : registrations) {
            synchronizeWithRetry(registration);
        }
    }

    public void register(FireflyJobRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        FireflyJobRegistration existing = registrations.stream()
                .filter(candidate -> candidate.id().equals(registration.id()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (existing.equals(registration)) {
                return;
            }
            throw new IllegalArgumentException("duplicate Firefly job id: " + registration.id());
        }
        registrations.add(registration);
    }

    public int registrationCount() {
        return registrations.size();
    }

    private void synchronizeWithRetry(FireflyJobRegistration registration) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            try {
                synchronize(registration);
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (attempt < properties.getMaxAttempts()) {
                    sleep(properties.getRetryDelay());
                }
            }
        }
        throw Objects.requireNonNull(lastFailure);
    }

    private void synchronize(FireflyJobRegistration registration) {
        if (executorClient.handlerRegistry().find(registration.handlerName()).isEmpty()) {
            throw new IllegalStateException("handler is not registered locally: " + registration.handlerName());
        }
        URI jobUri = jobUri(registration.id());
        HttpResponse<String> lookup = send(request(jobUri).GET().build());
        if (lookup.statusCode() == 200) {
            if (!properties.isUpdateExisting()) {
                log.info("Firefly job already exists; startup registration left it unchanged: job="
                        + registration.id() + ", executor=" + executorName);
                return;
            }
            expectSuccess(send(jsonRequest(jobUri).PUT(body(registration)).build()), 200, registration.id());
            log.info("Updated Firefly job from Spring declaration: job=" + registration.id()
                    + ", executor=" + executorName);
            return;
        }
        if (lookup.statusCode() != 404) {
            throw responseFailure("lookup", registration.id(), lookup);
        }
        HttpResponse<String> created = send(jsonRequest(jobsUri()).POST(body(registration)).build());
        if (created.statusCode() == 409) {
            if (properties.isUpdateExisting()) {
                expectSuccess(send(jsonRequest(jobUri).PUT(body(registration)).build()), 200, registration.id());
            }
            return;
        }
        expectSuccess(created, 201, registration.id());
        log.info("Created Firefly job from Spring declaration: job=" + registration.id()
                + ", executor=" + executorName + ", handler=" + registration.handlerName());
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(positive(properties.getRequestTimeout(), "requestTimeout"))
                .header("Accept", "application/json");
        if (properties.getAdminToken() != null && !properties.getAdminToken().isBlank()) {
            request.header("X-Firefly-Token", properties.getAdminToken());
        }
        return request;
    }

    private HttpRequest.Builder jsonRequest(URI uri) {
        return request(uri).header("Content-Type", "application/json; charset=utf-8");
    }

    private HttpRequest.BodyPublisher body(FireflyJobRegistration registration) {
        return HttpRequest.BodyPublishers.ofString(json(registration), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling Firefly Admin API", e);
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach Firefly Admin API at " + properties.getAdminUrl(), e);
        }
    }

    private void expectSuccess(HttpResponse<String> response, int expected, String jobId) {
        if (response.statusCode() != expected) {
            throw responseFailure("synchronize", jobId, response);
        }
    }

    private IllegalStateException responseFailure(
            String operation,
            String jobId,
            HttpResponse<String> response
    ) {
        return new IllegalStateException(operation + " job " + jobId + " returned HTTP "
                + response.statusCode() + ": " + response.body());
    }

    private URI jobsUri() {
        return URI.create(baseUrl() + "/api/jobs");
    }

    private URI jobUri(String jobId) {
        String encoded = URLEncoder.encode(jobId, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(baseUrl() + "/api/jobs/" + encoded);
    }

    private String baseUrl() {
        String value = requireNonBlank(properties.getAdminUrl(), "adminUrl");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String json(FireflyJobRegistration job) {
        StringBuilder json = new StringBuilder("{");
        append(json, "id", job.id());
        append(json, "name", job.name());
        append(json, "groupId", job.groupId());
        append(json, "executorName", executorName);
        append(json, "handlerName", job.handlerName());
        append(json, "cron", job.cron());
        append(json, "zoneId", job.zoneId());
        append(json, "enabled", Boolean.toString(job.enabled()), false);
        append(json, "dispatchMode", job.dispatchMode().name());
        append(json, "routingStrategy", job.routingStrategy().name());
        append(json, "completionPolicy", job.completionPolicy().name());
        append(json, "shardCount", Integer.toString(job.shardCount()), false);
        append(json, "routingKey", job.routingKey());
        append(json, "retryScope", job.retryScope().name());
        append(json, "retryMaxAttempts", Integer.toString(job.retryMaxAttempts()), false);
        for (Map.Entry<String, String> parameter : job.parameters().entrySet()) {
            append(json, "param." + parameter.getKey(), parameter.getValue());
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    private void append(StringBuilder json, String name, String value) {
        append(json, name, value, true);
    }

    private void append(StringBuilder json, String name, String value, boolean quoted) {
        json.append('"').append(escape(name)).append("\":");
        if (quoted) {
            json.append('"').append(escape(value)).append('"');
        } else {
            json.append(value);
        }
        json.append(',');
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private void validateConfiguration() {
        positive(properties.getRequestTimeout(), "requestTimeout");
        if (properties.getRetryDelay() == null || properties.getRetryDelay().isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        if (properties.getMaxAttempts() < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        HashSet<String> jobIds = new HashSet<>();
        for (FireflyJobRegistration registration : registrations) {
            if (!jobIds.add(registration.id())) {
                throw new IllegalArgumentException("duplicate Firefly job id: " + registration.id());
            }
        }
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying Firefly job registration", e);
        }
    }

    private String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
