package com.firefly.server;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.firefly.cluster.InMemoryNodeRegistry;
import com.firefly.cluster.NodeRegistry;
import com.firefly.engine.JobDispatcher;
import com.firefly.engine.SchedulerEngine;
import com.firefly.registry.InMemoryJobHandlerRegistry;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.store.InMemoryJobRepository;
import com.firefly.store.JobRepository;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SchedulerModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(JobRepository.class).to(InMemoryJobRepository.class).in(Singleton.class);
        bind(JobHandlerRegistry.class).to(InMemoryJobHandlerRegistry.class).in(Singleton.class);
        bind(NodeRegistry.class).to(InMemoryNodeRegistry.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Provides
    @Singleton
    ExecutorService workerPool() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Provides
    @Singleton
    JobDispatcher jobDispatcher(JobHandlerRegistry registry, ExecutorService workerPool, Clock clock) {
        return new JobDispatcher(registry, workerPool, clock);
    }

    @Provides
    @Singleton
    SchedulerEngine schedulerEngine(JobRepository repository, JobDispatcher dispatcher, Clock clock) {
        return new SchedulerEngine(repository, dispatcher, clock);
    }
}

