package com.firefly.server;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.firefly.cluster.InMemoryNodeRegistry;
import com.firefly.cluster.InMemoryShardManager;
import com.firefly.cluster.NodeRegistry;
import com.firefly.cluster.ShardManager;
import com.firefly.cluster.ShardOwnership;
import com.firefly.cluster.SchedulerShardConfig;
import com.firefly.catalog.InMemorySchedulerCatalog;
import com.firefly.catalog.SchedulerCatalog;
import com.firefly.engine.JobDispatcher;
import com.firefly.engine.SchedulerEngine;
import com.firefly.engine.SchedulerEngineOptions;
import com.firefly.executor.RemoteExecutionGateway;
import com.firefly.executor.ExecutorInstanceDirectory;
import com.firefly.executor.InMemoryExecutorInstanceDirectory;
import com.firefly.execution.ExecutionRepository;
import com.firefly.execution.InMemoryExecutionRepository;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.store.JobRepository;
import com.firefly.metrics.SchedulerMetrics;
import com.firefly.audit.AuditRepository;
import com.firefly.audit.InMemoryAuditRepository;
import com.firefly.store.JobHistoryRepository;
import com.firefly.store.InMemoryJobHistoryRepository;
import com.firefly.batch.BatchRepository;
import com.firefly.batch.InMemoryBatchRepository;
import com.firefly.schedule.SchedulingConditionEvaluator;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

public final class SchedulerModule extends AbstractModule {
    private final JobRepository jobRepository;
    private final NodeRegistry nodeRegistry;
    private final SchedulerCatalog schedulerCatalog;
    private final ShardManager shardManager;
    private final ExecutionRepository executionRepository;
    private final int shardCount;
    private final Clock runtimeClock;
    private final SchedulerMetrics metrics;
    private final SchedulerEngineOptions schedulerEngineOptions;
    private final AuditRepository auditRepository;
    private final JobHistoryRepository jobHistoryRepository;
    private final ExecutorInstanceDirectory executorInstanceDirectory;
    private final LocalWorkerOptions localWorkerOptions;
    private final BatchRepository batchRepository;
    private final SchedulingConditionEvaluator conditionEvaluator;

    public SchedulerModule() {
        this(SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    public SchedulerModule(int shardCount) {
        this(shardCount, SchedulerEngineOptions.defaults());
    }

    public SchedulerModule(int shardCount, SchedulerEngineOptions schedulerEngineOptions) {
        this(shardCount, schedulerEngineOptions, LocalWorkerOptions.defaults());
    }

    public SchedulerModule(
            int shardCount,
            SchedulerEngineOptions schedulerEngineOptions,
            LocalWorkerOptions localWorkerOptions
    ) {
        this(new InMemoryJobRepository(), new InMemoryNodeRegistry(), new InMemorySchedulerCatalog(),
                new InMemoryShardManager(), new InMemoryExecutionRepository(), shardCount,
                Clock.systemUTC(), new SchedulerMetrics(), schedulerEngineOptions,
                new InMemoryAuditRepository(), new InMemoryJobHistoryRepository(),
                new InMemoryExecutorInstanceDirectory(), localWorkerOptions);
    }

    public SchedulerModule(
            int shardCount,
            SchedulerEngineOptions schedulerEngineOptions,
            LocalWorkerOptions localWorkerOptions,
            SchedulingConditionEvaluator conditionEvaluator
    ) {
        this(new InMemoryJobRepository(), new InMemoryNodeRegistry(), new InMemorySchedulerCatalog(),
                new InMemoryShardManager(), new InMemoryExecutionRepository(), shardCount,
                Clock.systemUTC(), new SchedulerMetrics(), schedulerEngineOptions,
                new InMemoryAuditRepository(), new InMemoryJobHistoryRepository(),
                new InMemoryExecutorInstanceDirectory(), localWorkerOptions,
                new InMemoryBatchRepository(), conditionEvaluator);
    }

    public SchedulerModule(JobRepository jobRepository, NodeRegistry nodeRegistry) {
        this(jobRepository, nodeRegistry, new InMemorySchedulerCatalog(), new InMemoryShardManager(),
                new InMemoryExecutionRepository());
    }

    public SchedulerModule(JobRepository jobRepository, NodeRegistry nodeRegistry, SchedulerCatalog schedulerCatalog) {
        this(jobRepository, nodeRegistry, schedulerCatalog, new InMemoryShardManager(), new InMemoryExecutionRepository());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, new InMemoryExecutionRepository());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                SchedulerShardConfig.DEFAULT_SHARD_COUNT);
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                shardCount, Clock.systemUTC(), new SchedulerMetrics(), SchedulerEngineOptions.defaults());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount,
            Clock runtimeClock,
            SchedulerMetrics metrics
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                shardCount, runtimeClock, metrics, SchedulerEngineOptions.defaults());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount,
            Clock runtimeClock,
            SchedulerMetrics metrics,
            SchedulerEngineOptions schedulerEngineOptions
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                shardCount, runtimeClock, metrics, schedulerEngineOptions,
                new InMemoryAuditRepository(), new InMemoryJobHistoryRepository());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount,
            Clock runtimeClock,
            SchedulerMetrics metrics,
            SchedulerEngineOptions schedulerEngineOptions,
            AuditRepository auditRepository,
            JobHistoryRepository jobHistoryRepository
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                shardCount, runtimeClock, metrics, schedulerEngineOptions, auditRepository,
                jobHistoryRepository, new InMemoryExecutorInstanceDirectory());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount,
            Clock runtimeClock,
            SchedulerMetrics metrics,
            SchedulerEngineOptions schedulerEngineOptions,
            AuditRepository auditRepository,
            JobHistoryRepository jobHistoryRepository,
            ExecutorInstanceDirectory executorInstanceDirectory
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                shardCount, runtimeClock, metrics, schedulerEngineOptions, auditRepository,
                jobHistoryRepository, executorInstanceDirectory, LocalWorkerOptions.defaults());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount,
            Clock runtimeClock,
            SchedulerMetrics metrics,
            SchedulerEngineOptions schedulerEngineOptions,
            AuditRepository auditRepository,
            JobHistoryRepository jobHistoryRepository,
            ExecutorInstanceDirectory executorInstanceDirectory,
            LocalWorkerOptions localWorkerOptions
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                shardCount, runtimeClock, metrics, schedulerEngineOptions, auditRepository,
                jobHistoryRepository, executorInstanceDirectory, localWorkerOptions,
                new InMemoryBatchRepository());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount,
            Clock runtimeClock,
            SchedulerMetrics metrics,
            SchedulerEngineOptions schedulerEngineOptions,
            AuditRepository auditRepository,
            JobHistoryRepository jobHistoryRepository,
            ExecutorInstanceDirectory executorInstanceDirectory,
            LocalWorkerOptions localWorkerOptions,
            BatchRepository batchRepository
    ) {
        this(jobRepository, nodeRegistry, schedulerCatalog, shardManager, executionRepository,
                shardCount, runtimeClock, metrics, schedulerEngineOptions, auditRepository,
                jobHistoryRepository, executorInstanceDirectory, localWorkerOptions, batchRepository,
                SchedulingConditionEvaluator.allowAll());
    }

    public SchedulerModule(
            JobRepository jobRepository,
            NodeRegistry nodeRegistry,
            SchedulerCatalog schedulerCatalog,
            ShardManager shardManager,
            ExecutionRepository executionRepository,
            int shardCount,
            Clock runtimeClock,
            SchedulerMetrics metrics,
            SchedulerEngineOptions schedulerEngineOptions,
            AuditRepository auditRepository,
            JobHistoryRepository jobHistoryRepository,
            ExecutorInstanceDirectory executorInstanceDirectory,
            LocalWorkerOptions localWorkerOptions,
            BatchRepository batchRepository,
            SchedulingConditionEvaluator conditionEvaluator
    ) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository");
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
        this.schedulerCatalog = Objects.requireNonNull(schedulerCatalog, "schedulerCatalog");
        this.shardManager = Objects.requireNonNull(shardManager, "shardManager");
        this.executionRepository = Objects.requireNonNull(executionRepository, "executionRepository");
        this.shardCount = new SchedulerShardConfig(shardCount).shardCount();
        this.runtimeClock = Objects.requireNonNull(runtimeClock, "runtimeClock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.schedulerEngineOptions = Objects.requireNonNull(schedulerEngineOptions, "schedulerEngineOptions");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        this.jobHistoryRepository = Objects.requireNonNull(jobHistoryRepository, "jobHistoryRepository");
        this.executorInstanceDirectory = Objects.requireNonNull(executorInstanceDirectory, "executorInstanceDirectory");
        this.localWorkerOptions = Objects.requireNonNull(localWorkerOptions, "localWorkerOptions");
        this.batchRepository = Objects.requireNonNull(batchRepository, "batchRepository");
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator");
    }

    @Override
    protected void configure() {
        bind(JobRepository.class).toInstance(jobRepository);
        bind(NodeRegistry.class).toInstance(nodeRegistry);
        bind(SchedulerCatalog.class).toInstance(schedulerCatalog);
        bind(ShardManager.class).toInstance(shardManager);
        bind(ExecutionRepository.class).toInstance(executionRepository);
        bind(SchedulerMetrics.class).toInstance(metrics);
        bind(AuditRepository.class).toInstance(auditRepository);
        bind(JobHistoryRepository.class).toInstance(jobHistoryRepository);
        bind(ExecutorInstanceDirectory.class).toInstance(executorInstanceDirectory);
        bind(BatchRepository.class).toInstance(batchRepository);
        bind(SchedulingConditionEvaluator.class).toInstance(conditionEvaluator);
        bind(JobHandlerRegistry.class).to(InMemoryJobHandlerRegistry.class).in(Singleton.class);
        bind(SwitchableRemoteExecutionGateway.class).in(Singleton.class);
        bind(RemoteExecutionGateway.class).to(SwitchableRemoteExecutionGateway.class);
        bind(SwitchableShardOwnership.class).in(Singleton.class);
        bind(ShardOwnership.class).to(SwitchableShardOwnership.class);
    }

    @Provides
    @Singleton
    Clock clock() {
        return runtimeClock;
    }

    @Provides
    @Singleton
    ExecutorService workerPool() {
        return new BoundedVirtualThreadExecutor(localWorkerOptions, metrics);
    }

    @Provides
    @Singleton
    JobDispatcher jobDispatcher(
            JobHandlerRegistry registry,
            ExecutorService workerPool,
            Clock clock,
            RemoteExecutionGateway remoteExecutionGateway,
            ExecutionRepository executionRepository,
            SchedulerMetrics metrics
    ) {
        return new JobDispatcher(
                registry, workerPool, clock, remoteExecutionGateway, executionRepository, metrics
        );
    }

    @Provides
    @Singleton
    SchedulerEngine schedulerEngine(
            JobRepository repository,
            JobDispatcher dispatcher,
            Clock clock,
            ShardOwnership shardOwnership,
            SchedulerMetrics metrics,
            SchedulingConditionEvaluator conditionEvaluator
    ) {
        return new SchedulerEngine(
                repository, dispatcher, clock, shardOwnership, shardCount, true, metrics, schedulerEngineOptions,
                conditionEvaluator
        );
    }
}
