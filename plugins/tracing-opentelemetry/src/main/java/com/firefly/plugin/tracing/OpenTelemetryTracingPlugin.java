package com.firefly.plugin.tracing;

import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginCompatibility;
import com.firefly.plugin.FireflyPluginContext;
import com.firefly.plugin.FireflyPluginRuntimeCompatibility;
import com.firefly.tracing.FireflyTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/** Firefly plugin that configures an OTLP exporter while the core remains exporter-agnostic. */
public final class OpenTelemetryTracingPlugin implements FireflyPlugin {
    private final OpenTelemetryTracingOptions options;
    private final SpanExporter exporterOverride;
    private FireflyTelemetry.Registration registration;
    private SdkTracerProvider provider;

    public OpenTelemetryTracingPlugin() {
        this(null, null);
    }

    public OpenTelemetryTracingPlugin(OpenTelemetryTracingOptions options) {
        this(options, null);
    }

    OpenTelemetryTracingPlugin(OpenTelemetryTracingOptions options, SpanExporter exporterOverride) {
        this.options = options;
        this.exporterOverride = exporterOverride;
    }

    @Override
    public String id() {
        return "tracing-opentelemetry";
    }

    @Override
    public String displayName() {
        return "OpenTelemetry tracing";
    }

    @Override
    public String description() {
        return "Correlates scheduler, outbox, gateway, executor, and result spans with W3C trace context";
    }

    @Override
    public FireflyPluginCompatibility compatibility() {
        return new FireflyPluginCompatibility(1, 1);
    }

    @Override
    public FireflyPluginRuntimeCompatibility runtimeCompatibility() {
        return new FireflyPluginRuntimeCompatibility(
                compatibility(), "1.0.0", "1.0.7", 1, 2, 12, 12, true
        );
    }

    @Override
    public synchronized void start(FireflyPluginContext context) {
        if (registration != null) throw new IllegalStateException("tracing plugin is already started");
        OpenTelemetryTracingOptions effective = options == null
                ? OpenTelemetryTracingOptions.from(context.configuration()) : options;
        SpanExporter exporter = exporterOverride != null ? exporterOverride : OtlpHttpSpanExporter.builder()
                .setEndpoint(effective.endpoint())
                .setTimeout(effective.exportTimeout())
                .setHeaders(effective::headers)
                .build();
        Sampler rootSampler = effective.samplingRatio() >= 1.0
                        ? Sampler.alwaysOn() : effective.samplingRatio() <= 0
                        ? Sampler.alwaysOff() : Sampler.traceIdRatioBased(effective.samplingRatio());
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.parentBased(rootSampler))
                .setResource(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), effective.serviceName(),
                        AttributeKey.stringKey("service.version"), version()
                )))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(java.time.Duration.ofSeconds(1)).build())
                .build();
        OpenTelemetry telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        try {
            registration = FireflyTelemetry.install(telemetry);
        } catch (RuntimeException failure) {
            provider.close();
            provider = null;
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (registration != null) registration.close();
        registration = null;
        if (provider != null) provider.close();
        provider = null;
    }
}
