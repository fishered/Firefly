package com.firefly.spring.job;

import com.firefly.domain.ExecutionContext;
import com.firefly.spring.annotation.FireflyJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FireflyJobAnnotationBeanPostProcessorTest {
    @Test
    void supportsNoArgumentHandlerMethods() throws Exception {
        NoArgumentJobs bean = new NoArgumentJobs();
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();

        processor.postProcessAfterInitialization(bean, "noArgumentJobs");
        var method = processor.discoveredMethods().getFirst();
        method.handler().handle(context());

        assertEquals(1, bean.invocations.get());
        assertEquals(NoArgumentJobs.class.getName() + "#cleanup", method.handlerName());
    }

    @Test
    void derivesJobIdFromFullyQualifiedMethodName() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();
        processor.postProcessAfterInitialization(new DefaultIdJobs(), "defaultIdJobs");

        var discovered = processor.discoveredMethods().getFirst();
        assertEquals(DefaultIdJobs.class.getName() + "#billing",
                discovered.registration(discovered.declarations().getFirst()).id());
        assertEquals(DefaultIdJobs.class.getName() + "#billing", discovered.handlerName());
    }

    @Test
    void requiresKeysForRepeatedSchedules() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                processor.postProcessAfterInitialization(new RepeatedDefaultIdJobs(), "repeatedJobs"));
        assertTrue(failure.getMessage().contains("require non-blank keys"));
    }

    @Test
    void derivesRepeatedJobIdsFromEntrypointAndLocalKeys() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();
        processor.postProcessAfterInitialization(new RepeatedKeyJobs(), "repeatedKeyJobs");

        var discovered = processor.discoveredMethods().getFirst();
        String entrypoint = RepeatedKeyJobs.class.getName() + "#billing";
        assertEquals(entrypoint, discovered.handlerName());
        assertEquals(entrypoint + ":incremental", discovered.registration(discovered.declarations().get(0)).id());
        assertEquals(entrypoint + ":full", discovered.registration(discovered.declarations().get(1)).id());
    }

    @Test
    void rejectsDuplicateScheduleKeys() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                processor.postProcessAfterInitialization(new DuplicateKeyJobs(), "duplicateKeyJobs"));

        assertTrue(failure.getMessage().contains("duplicate @FireflyJob key"));
    }

    @Test
    void rejectsKeysThatAreNotStableIdentifierSegments() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                processor.postProcessAfterInitialization(new InvalidKeyJobs(), "invalidKeyJobs"));

        assertTrue(failure.getMessage().contains("key must match"));
    }

    @Test
    void boundsLongGeneratedJobIdsWithAStableDigest() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();
        processor.postProcessAfterInitialization(
                new BillingReconciliationJobsWithAnIntentionallyLongClassNameForIdentifierBoundaryVerification(),
                "longIdentifierJobs"
        );

        var discovered = processor.discoveredMethods().getFirst();
        String first = discovered.registration(discovered.declarations().getFirst()).id();
        String second = discovered.registration(discovered.declarations().getFirst()).id();

        assertEquals(128, first.length());
        assertEquals(first, second);
        assertTrue(first.contains("~"));
    }

    @Test
    void rejectsInvalidMethodSignaturesDuringBeanInitialization() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                processor.postProcessAfterInitialization(new InvalidJobs(), "invalidJobs")
        );

        assertTrue(failure.getMessage().contains("must return void"));
    }

    @Test
    void rejectsInvalidAnnotationZoneIdsDuringBeanInitialization() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                processor.postProcessAfterInitialization(new InvalidZoneJobs(), "invalidZoneJobs")
        );

        assertTrue(failure.getMessage().contains("invalid zoneId 'Asia/ShangHai'"));
        assertTrue(failure.getMessage().contains("InvalidZoneJobs.invalidZone"));
    }

    @Test
    void rejectsInvalidProgrammaticRegistrationZoneIds() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                FireflyJobRegistration.builder("billing", "billingHandler", "0 0 * * * *")
                        .zoneId("Mars/Olympus")
                        .build()
        );

        assertTrue(failure.getMessage().contains("invalid zoneId 'Mars/Olympus'"));
        assertTrue(failure.getMessage().contains("Firefly job billing"));
    }

    private ExecutionContext context() {
        Instant now = Instant.now();
        return new ExecutionContext(
                "execution-1", "cleanup-job", "cleanup", now, now, now, Map.of()
        );
    }

    static class NoArgumentJobs {
        private final AtomicInteger invocations = new AtomicInteger();

        @FireflyJob(cron = "0 0 * * * *")
        public void cleanup() {
            invocations.incrementAndGet();
        }
    }

    static class InvalidJobs {
        @FireflyJob(cron = "0 0 * * * *")
        public String invalid() {
            return "invalid";
        }
    }

    static class InvalidZoneJobs {
        @FireflyJob(cron = "0 0 * * * *", zoneId = "Asia/ShangHai")
        public void invalidZone() { }
    }

    static class DefaultIdJobs {
        @FireflyJob(cron = "0 0 * * * *")
        public void billing() { }
    }

    static class RepeatedDefaultIdJobs {
        @FireflyJob(cron = "0 0 * * * *")
        @FireflyJob(cron = "0 30 * * * *")
        public void billing() { }
    }

    static class RepeatedKeyJobs {
        @FireflyJob(key = "incremental", cron = "0 0 * * * *")
        @FireflyJob(key = "full", cron = "0 30 * * * *")
        public void billing() { }
    }

    static class DuplicateKeyJobs {
        @FireflyJob(key = "daily", cron = "0 0 * * * *")
        @FireflyJob(key = "daily", cron = "0 30 * * * *")
        public void billing() { }
    }

    static class InvalidKeyJobs {
        @FireflyJob(key = "daily billing", cron = "0 0 * * * *")
        public void billing() { }
    }

    static class BillingReconciliationJobsWithAnIntentionallyLongClassNameForIdentifierBoundaryVerification {
        @FireflyJob(
                key = "a-very-long-yet-valid-local-schedule-key-for-database-limit",
                cron = "0 0 * * * *"
        )
        public void reconcileAllPrimaryAndSecondaryTenantBillingTransactions() { }
    }
}
