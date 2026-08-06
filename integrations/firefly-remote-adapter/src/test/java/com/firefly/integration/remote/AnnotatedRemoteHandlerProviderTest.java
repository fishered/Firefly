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

        assertEquals(java.util.Set.of("billing"), registry.names());
    }

    @Test
    void rejectsDuplicateNamesAcrossExplicitObjects() {
        RemoteHandlerProvider provider = RemoteHandlerProvider.annotated(
                new BillingHandlers(), new DuplicateBillingHandlers()
        );

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
        @FireflyHandler(handlerName = "billing")
        private void bill(ExecutionContext context) {
        }
    }

    static class DuplicateBillingHandlers {
        @FireflyHandler(handlerName = "billing")
        void bill() {
        }
    }

    static class InvalidHandlers {
        @FireflyHandler(handlerName = "invalid")
        String invalid() {
            return "invalid";
        }
    }
}
