package com.firefly.plugin.tracing;

import java.time.Duration;
import java.util.Map;

/** Configuration for the optional OTLP HTTP tracing exporter. */
public record OpenTelemetryTracingOptions(
        String endpoint,
        String serviceName,
        double samplingRatio,
        Duration exportTimeout,
        Map<String, String> headers
) {
    public OpenTelemetryTracingOptions {
        endpoint = endpoint == null || endpoint.isBlank()
                ? "http://127.0.0.1:4318/v1/traces" : endpoint.trim();
        serviceName = serviceName == null || serviceName.isBlank() ? "firefly" : serviceName.trim();
        if (samplingRatio < 0 || samplingRatio > 1 || Double.isNaN(samplingRatio)) {
            throw new IllegalArgumentException("samplingRatio must be between 0 and 1");
        }
        exportTimeout = exportTimeout == null ? Duration.ofSeconds(10) : exportTimeout;
        if (exportTimeout.isNegative() || exportTimeout.isZero()) {
            throw new IllegalArgumentException("exportTimeout must be positive");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static OpenTelemetryTracingOptions defaults() {
        return new OpenTelemetryTracingOptions(
                "http://127.0.0.1:4318/v1/traces", "firefly", 1.0,
                Duration.ofSeconds(10), Map.of()
        );
    }

    public static OpenTelemetryTracingOptions from(com.firefly.plugin.FireflyPluginConfiguration configuration) {
        OpenTelemetryTracingOptions defaults = defaults();
        String headerValue = configuration.property("firefly.tracing.opentelemetry.headers", "");
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (!headerValue.isBlank()) {
            for (String entry : headerValue.split(",")) {
                int separator = entry.indexOf('=');
                if (separator > 0) headers.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
            }
        }
        return new OpenTelemetryTracingOptions(
                configuration.property("firefly.tracing.opentelemetry.endpoint", defaults.endpoint()),
                configuration.property("firefly.tracing.opentelemetry.service-name", defaults.serviceName()),
                Double.parseDouble(configuration.property(
                        "firefly.tracing.opentelemetry.sampling-ratio", Double.toString(defaults.samplingRatio()))),
                Duration.ofMillis(Long.parseLong(configuration.property(
                        "firefly.tracing.opentelemetry.export-timeout-ms",
                        Long.toString(defaults.exportTimeout().toMillis())))),
                headers
        );
    }
}
