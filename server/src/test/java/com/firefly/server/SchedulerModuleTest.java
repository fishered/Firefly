package com.firefly.server;

import com.google.inject.Guice;
import com.firefly.engine.SchedulerEngine;
import com.firefly.registry.JobHandlerRegistry;
import com.firefly.store.JobRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SchedulerModuleTest {
    @Test
    void createsCoreServicesWithGuice() {
        var injector = Guice.createInjector(new SchedulerModule());

        assertNotNull(injector.getInstance(JobRepository.class));
        assertNotNull(injector.getInstance(JobHandlerRegistry.class));
        assertNotNull(injector.getInstance(SchedulerEngine.class));
    }
}

