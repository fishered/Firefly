package com.firefly.plugin.tracing;

import com.firefly.plugin.FireflyPluginContext;
import com.firefly.tracing.FireflyTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OpenTelemetryTracingPluginTest {
    @Test
    void installsExporterAndPropagatesW3cContext() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetryTracingPlugin plugin = new OpenTelemetryTracingPlugin(
                new OpenTelemetryTracingOptions("http://unused", "test-firefly", 1.0,
                        Duration.ofSeconds(1), Map.of()), exporter
        );
        plugin.start(FireflyPluginContext.builder().build());
        Span scheduler = FireflyTelemetry.tracer().spanBuilder("scheduler").startSpan();
        var carrier = FireflyTelemetry.inject(Context.root().with(scheduler));
        assertFalse(carrier.isEmpty());
        Span outbox = FireflyTelemetry.tracer().spanBuilder("outbox")
                .setParent(FireflyTelemetry.extract(carrier)).startSpan();
        outbox.end();
        scheduler.end();
        ((OpenTelemetrySdk) FireflyTelemetry.openTelemetry()).getSdkTracerProvider()
                .forceFlush().join(1, TimeUnit.SECONDS);

        assertEquals(2, exporter.getFinishedSpanItems().size());
        assertEquals("outbox", exporter.getFinishedSpanItems().getFirst().getName());
        plugin.close();
    }
}
