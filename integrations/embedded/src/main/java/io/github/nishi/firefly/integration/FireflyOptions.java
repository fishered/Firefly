package io.github.nishi.firefly.integration;

import lombok.Builder;

import java.time.Clock;
import java.util.Objects;

/**
 * Configures an embedded Firefly scheduler without tying integration code to any container.
 */
@Builder(builderMethodName = "newBuilder", builderClassName = "Builder")
public record FireflyOptions(
        Clock clock,
        int workerThreads,
        String workerThreadNamePrefix
) {
    /**
     * Applies validation to options created by Lombok, tests, or direct constructors.
     */
    public FireflyOptions {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(workerThreadNamePrefix, "workerThreadNamePrefix");
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be greater than 0");
        }
        if (workerThreadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("workerThreadNamePrefix must not be blank");
        }
    }

    /**
     * Keeps the zero-config path useful for small services while still allowing explicit tuning.
     */
    public static Builder builder() {
        int defaultWorkers = Math.max(2, Runtime.getRuntime().availableProcessors());
        return newBuilder()
                .clock(Clock.systemUTC())
                .workerThreads(defaultWorkers)
                .workerThreadNamePrefix("firefly-worker");
    }
}
