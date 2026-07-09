package com.firefly.plugin;

import com.firefly.cluster.NodeRegistry;
import com.firefly.executor.ExecutorRegistry;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides optional runtime services to plugins without making the scheduler core depend on them.
 */
public final class FireflyPluginContext {
    private final Clock clock;
    private final JobRepository jobRepository;
    private final NodeRegistry nodeRegistry;
    private final ExecutorRegistry executorRegistry;

    private FireflyPluginContext(Builder builder) {
        this.clock = Objects.requireNonNull(builder.clock, "clock");
        this.jobRepository = builder.jobRepository;
        this.nodeRegistry = builder.nodeRegistry;
        this.executorRegistry = builder.executorRegistry;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Clock clock() {
        return clock;
    }

    public Optional<JobRepository> jobRepository() {
        return Optional.ofNullable(jobRepository);
    }

    public Optional<NodeRegistry> nodeRegistry() {
        return Optional.ofNullable(nodeRegistry);
    }

    public Optional<ExecutorRegistry> executorRegistry() {
        return Optional.ofNullable(executorRegistry);
    }

    public static final class Builder {
        private Clock clock = Clock.systemUTC();
        private JobRepository jobRepository;
        private NodeRegistry nodeRegistry;
        private ExecutorRegistry executorRegistry;

        private Builder() {
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder jobRepository(JobRepository jobRepository) {
            this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
            return this;
        }

        public Builder nodeRegistry(NodeRegistry nodeRegistry) {
            this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
            return this;
        }

        public Builder executorRegistry(ExecutorRegistry executorRegistry) {
            this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
            return this;
        }

        public FireflyPluginContext build() {
            return new FireflyPluginContext(this);
        }
    }
}
