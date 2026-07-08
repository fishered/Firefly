package io.github.nishi.firefly.server;

import com.google.inject.Guice;
import io.github.nishi.firefly.engine.SchedulerEngine;
import io.github.nishi.firefly.registry.JobHandlerRegistry;
import io.github.nishi.firefly.store.JobRepository;
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

