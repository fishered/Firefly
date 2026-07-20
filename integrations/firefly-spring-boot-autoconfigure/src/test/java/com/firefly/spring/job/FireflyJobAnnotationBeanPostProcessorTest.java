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
        assertEquals("cleanup", method.handlerName(method.declarations().getFirst()));
    }

    @Test
    void rejectsInvalidMethodSignaturesDuringBeanInitialization() {
        FireflyJobAnnotationBeanPostProcessor processor = new FireflyJobAnnotationBeanPostProcessor();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                processor.postProcessAfterInitialization(new InvalidJobs(), "invalidJobs")
        );

        assertTrue(failure.getMessage().contains("must return void"));
    }

    private ExecutionContext context() {
        Instant now = Instant.now();
        return new ExecutionContext(
                "execution-1", "cleanup-job", "cleanup", now, now, now, Map.of()
        );
    }

    static class NoArgumentJobs {
        private final AtomicInteger invocations = new AtomicInteger();

        @FireflyJob(id = "cleanup-job", cron = "0 0 * * * *")
        public void cleanup() {
            invocations.incrementAndGet();
        }
    }

    static class InvalidJobs {
        @FireflyJob(id = "invalid-job", cron = "0 0 * * * *")
        public String invalid() {
            return "invalid";
        }
    }
}
