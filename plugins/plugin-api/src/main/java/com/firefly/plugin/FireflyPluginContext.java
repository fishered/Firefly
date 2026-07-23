package com.firefly.plugin;

import com.firefly.cluster.NodeRegistry;
import com.firefly.catalog.SchedulerCatalog;
import com.firefly.executor.ExecutorRegistry;
import com.firefly.execution.ExecutionRepository;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.store.JobRepository;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.audit.AuditRepository;
import com.firefly.store.JobHistoryRepository;
import com.firefly.security.AdminUserRepository;
import com.firefly.security.IntegrationKeyRepository;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Provides optional runtime services to plugins without making the scheduler core depend on them.
 */
public final class FireflyPluginContext {
    private final Clock clock;
    private final JobRepository jobRepository;
    private final JobHandlerRegistry jobHandlerRegistry;
    private final NodeRegistry nodeRegistry;
    private final ExecutorRegistry executorRegistry;
    private final SchedulerCatalog schedulerCatalog;
    private final ExecutionRepository executionRepository;
    private final RemoteExecutorDispatcher remoteExecutorDispatcher;
    private final ExecutionCancellationDispatcher executionCancellationDispatcher;
    private final SchedulerMetrics schedulerMetrics;
    private final int schedulerShardCount;
    private final AuditRepository auditRepository;
    private final JobHistoryRepository jobHistoryRepository;
    private final ExecutorIsolationDispatcher executorIsolationDispatcher;
    private final NodeDrainStatusProvider nodeDrainStatusProvider;
    private final AdminUserRepository adminUserRepository;
    private final IntegrationKeyRepository integrationKeyRepository;
    private final PluginStatusProvider pluginStatusProvider;
    private final FireflyPluginConfiguration configuration;

    private FireflyPluginContext(Builder builder) {
        this.clock = Objects.requireNonNull(builder.clock, "clock");
        this.jobRepository = builder.jobRepository;
        this.jobHandlerRegistry = builder.jobHandlerRegistry;
        this.nodeRegistry = builder.nodeRegistry;
        this.executorRegistry = builder.executorRegistry;
        this.schedulerCatalog = builder.schedulerCatalog;
        this.executionRepository = builder.executionRepository;
        this.remoteExecutorDispatcher = builder.remoteExecutorDispatcher;
        this.executionCancellationDispatcher = builder.executionCancellationDispatcher;
        this.schedulerMetrics = builder.schedulerMetrics;
        this.schedulerShardCount = builder.schedulerShardCount;
        this.auditRepository = builder.auditRepository;
        this.jobHistoryRepository = builder.jobHistoryRepository;
        this.executorIsolationDispatcher = builder.executorIsolationDispatcher;
        this.nodeDrainStatusProvider = builder.nodeDrainStatusProvider;
        this.adminUserRepository = builder.adminUserRepository;
        this.integrationKeyRepository = builder.integrationKeyRepository;
        this.pluginStatusProvider = builder.pluginStatusProvider;
        this.configuration = builder.configuration;
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

    public Optional<JobHandlerRegistry> jobHandlerRegistry() {
        return Optional.ofNullable(jobHandlerRegistry);
    }

    public Optional<NodeRegistry> nodeRegistry() {
        return Optional.ofNullable(nodeRegistry);
    }

    public Optional<ExecutorRegistry> executorRegistry() {
        return Optional.ofNullable(executorRegistry);
    }

    public Optional<SchedulerCatalog> schedulerCatalog() {
        return Optional.ofNullable(schedulerCatalog);
    }

    public Optional<ExecutionRepository> executionRepository() {
        return Optional.ofNullable(executionRepository);
    }

    public Optional<RemoteExecutorDispatcher> remoteExecutorDispatcher() {
        return Optional.ofNullable(remoteExecutorDispatcher);
    }

    public Optional<ExecutionCancellationDispatcher> executionCancellationDispatcher() {
        return Optional.ofNullable(executionCancellationDispatcher);
    }

    public Optional<SchedulerMetrics> schedulerMetrics() {
        return Optional.ofNullable(schedulerMetrics);
    }

    public int schedulerShardCount() {
        return schedulerShardCount;
    }

    public Optional<AuditRepository> auditRepository() {
        return Optional.ofNullable(auditRepository);
    }

    public Optional<JobHistoryRepository> jobHistoryRepository() {
        return Optional.ofNullable(jobHistoryRepository);
    }

    public Optional<ExecutorIsolationDispatcher> executorIsolationDispatcher() {
        return Optional.ofNullable(executorIsolationDispatcher);
    }

    public Optional<NodeDrainStatusProvider> nodeDrainStatusProvider() {
        return Optional.ofNullable(nodeDrainStatusProvider);
    }

    public Optional<AdminUserRepository> adminUserRepository() {
        return Optional.ofNullable(adminUserRepository);
    }

    public Optional<IntegrationKeyRepository> integrationKeyRepository() {
        return Optional.ofNullable(integrationKeyRepository);
    }

    public Optional<PluginStatusProvider> pluginStatusProvider() {
        return Optional.ofNullable(pluginStatusProvider);
    }

    public FireflyPluginConfiguration configuration() {
        return configuration;
    }

    public static final class Builder {
        private Clock clock = Clock.systemUTC();
        private JobRepository jobRepository;
        private JobHandlerRegistry jobHandlerRegistry;
        private NodeRegistry nodeRegistry;
        private ExecutorRegistry executorRegistry;
        private SchedulerCatalog schedulerCatalog;
        private ExecutionRepository executionRepository;
        private RemoteExecutorDispatcher remoteExecutorDispatcher;
        private ExecutionCancellationDispatcher executionCancellationDispatcher;
        private SchedulerMetrics schedulerMetrics;
        private int schedulerShardCount = 1;
        private AuditRepository auditRepository;
        private JobHistoryRepository jobHistoryRepository;
        private ExecutorIsolationDispatcher executorIsolationDispatcher;
        private NodeDrainStatusProvider nodeDrainStatusProvider;
        private AdminUserRepository adminUserRepository;
        private IntegrationKeyRepository integrationKeyRepository;
        private PluginStatusProvider pluginStatusProvider;
        private FireflyPluginConfiguration configuration = FireflyPluginConfiguration.empty();

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

        public Builder jobHandlerRegistry(JobHandlerRegistry jobHandlerRegistry) {
            this.jobHandlerRegistry = Objects.requireNonNull(jobHandlerRegistry, "jobHandlerRegistry");
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

        public Builder schedulerCatalog(SchedulerCatalog schedulerCatalog) {
            this.schedulerCatalog = Objects.requireNonNull(schedulerCatalog, "schedulerCatalog");
            return this;
        }

        public Builder executionRepository(ExecutionRepository executionRepository) {
            this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
            return this;
        }

        public Builder remoteExecutorDispatcher(RemoteExecutorDispatcher remoteExecutorDispatcher) {
            this.remoteExecutorDispatcher = Objects.requireNonNull(remoteExecutorDispatcher, "remoteExecutorDispatcher");
            return this;
        }

        public Builder executionCancellationDispatcher(
                ExecutionCancellationDispatcher executionCancellationDispatcher
        ) {
            this.executionCancellationDispatcher = Objects.requireNonNull(
                    executionCancellationDispatcher, "executionCancellationDispatcher"
            );
            return this;
        }

        public Builder schedulerMetrics(SchedulerMetrics schedulerMetrics) {
            this.schedulerMetrics = Objects.requireNonNull(schedulerMetrics, "schedulerMetrics");
            return this;
        }

        public Builder schedulerShardCount(int schedulerShardCount) {
            if (schedulerShardCount < 1) throw new IllegalArgumentException("schedulerShardCount must be positive");
            this.schedulerShardCount = schedulerShardCount;
            return this;
        }

        public Builder auditRepository(AuditRepository auditRepository) {
            this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
            return this;
        }

        public Builder jobHistoryRepository(JobHistoryRepository jobHistoryRepository) {
            this.jobHistoryRepository = Objects.requireNonNull(jobHistoryRepository, "jobHistoryRepository");
            return this;
        }

        public Builder executorIsolationDispatcher(ExecutorIsolationDispatcher dispatcher) {
            this.executorIsolationDispatcher = Objects.requireNonNull(dispatcher, "executorIsolationDispatcher");
            return this;
        }

        public Builder nodeDrainStatusProvider(NodeDrainStatusProvider provider) {
            this.nodeDrainStatusProvider = Objects.requireNonNull(provider, "nodeDrainStatusProvider");
            return this;
        }

        public Builder adminUserRepository(AdminUserRepository repository) {
            this.adminUserRepository = Objects.requireNonNull(repository, "adminUserRepository");
            return this;
        }

        public Builder integrationKeyRepository(IntegrationKeyRepository repository) {
            this.integrationKeyRepository = Objects.requireNonNull(repository, "integrationKeyRepository");
            return this;
        }

        public Builder pluginStatusProvider(PluginStatusProvider provider) {
            this.pluginStatusProvider = Objects.requireNonNull(provider, "pluginStatusProvider");
            return this;
        }

        public Builder configuration(FireflyPluginConfiguration configuration) {
            this.configuration = Objects.requireNonNull(configuration, "configuration");
            return this;
        }

        public FireflyPluginContext build() {
            return new FireflyPluginContext(this);
        }
    }
}
