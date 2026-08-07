package com.firefly.integration.remote;

import com.firefly.domain.ExecutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotatedRemoteHandlerProviderTest {
    @Test
    void scansOnlyExplicitlySuppliedObjects() {
        RemoteHandlerRegistry registry = new RemoteHandlerRegistry();

        RemoteHandlerProvider.annotated(new BillingHandlers()).register(registry);

        assertEquals(
                java.util.Set.of(BillingHandlers.class.getName() + "#bill"),
                registry.names()
        );
    }

    @Test
    void exposesNoManuallyMaintainedAnnotationAttributes() {
        assertEquals(0, FireflyHandler.class.getDeclaredMethods().length);
    }

    @Test
    void rejectsAnnotatedOverloadsWithTheSameAutomaticName() {
        RemoteHandlerProvider provider = RemoteHandlerProvider.annotated(new DuplicateBillingHandlers());

        assertThrows(IllegalArgumentException.class, () -> provider.register(new RemoteHandlerRegistry()));
    }

    @Test
    void rejectsMethodsThatCannotBeJobHandlers() {
        RemoteHandlerProvider provider = RemoteHandlerProvider.annotated(new InvalidHandlers());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> provider.register(new RemoteHandlerRegistry())
        );
        assertTrue(failure.getMessage().contains("must return void"));
    }

    static class BillingHandlers {
        @FireflyHandler
        private void bill(ExecutionContext context) {
        }
    }

    static class DuplicateBillingHandlers {
        @FireflyHandler
        void bill() {
        }

        @FireflyHandler
        void bill(ExecutionContext context) {
        }
    }

    static class InvalidHandlers {
        @FireflyHandler
        String invalid() {
            return "invalid";
        }
    }
}
