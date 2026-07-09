package io.github.nishi.firefly.plugin.admin;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AdminWebOptionsTest {
    @Test
    void defaultsUseLocalhostAndAdminPort() {
        AdminWebOptions options = AdminWebOptions.defaults();

        assertEquals("127.0.0.1", options.host());
        assertEquals(9710, options.port());
    }

    @Test
    void rejectsInvalidPort() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AdminWebOptions("127.0.0.1", 0, Duration.ofSeconds(30))
        );
    }
}
