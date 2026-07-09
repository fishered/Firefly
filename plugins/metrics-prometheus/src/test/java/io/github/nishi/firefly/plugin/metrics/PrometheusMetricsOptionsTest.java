package io.github.nishi.firefly.plugin.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PrometheusMetricsOptionsTest {
    @Test
    void defaultsExposeMetricsPath() {
        PrometheusMetricsOptions options = PrometheusMetricsOptions.defaults();

        assertEquals("127.0.0.1", options.host());
        assertEquals(9711, options.port());
        assertEquals("/metrics", options.path());
    }

    @Test
    void rejectsPathWithoutSlash() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PrometheusMetricsOptions("127.0.0.1", 9711, "metrics", Duration.ofSeconds(30))
        );
    }
}
