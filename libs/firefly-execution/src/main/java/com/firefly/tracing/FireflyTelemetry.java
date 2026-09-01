package com.firefly.tracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Shared OpenTelemetry access point. It is no-op until a plugin, SDK, or Java agent supplies telemetry. */
public final class FireflyTelemetry {
    public static final String INSTRUMENTATION_SCOPE = "com.firefly";
    private static final AtomicReference<OpenTelemetry> INSTALLED = new AtomicReference<>();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    private FireflyTelemetry() {
    }

    public static OpenTelemetry openTelemetry() {
        OpenTelemetry installed = INSTALLED.get();
        return installed == null ? GlobalOpenTelemetry.get() : installed;
    }

    public static Tracer tracer() {
        return openTelemetry().getTracer(INSTRUMENTATION_SCOPE);
    }

    public static Context extract(TraceCarrier carrier) {
        return extract(carrier == null ? Map.of() : carrier.values());
    }

    public static Context extract(Map<String, String> carrier) {
        Map<String, String> safeCarrier = TraceCarrier.sanitize(carrier);
        return openTelemetry().getPropagators().getTextMapPropagator()
                .extract(Context.root(), safeCarrier, GETTER);
    }

    public static TraceCarrier inject(Context context) {
        LinkedHashMap<String, String> carrier = new LinkedHashMap<>();
        inject(context, carrier);
        return new TraceCarrier(carrier);
    }

    public static void inject(Context context, Map<String, String> carrier) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(carrier, "carrier");
        openTelemetry().getPropagators().getTextMapPropagator().inject(context, carrier, SETTER);
    }

    /** Installs plugin-owned telemetry without changing the application's GlobalOpenTelemetry instance. */
    public static Registration install(OpenTelemetry openTelemetry) {
        Objects.requireNonNull(openTelemetry, "openTelemetry");
        if (!INSTALLED.compareAndSet(null, openTelemetry)) {
            throw new IllegalStateException("Firefly telemetry is already installed");
        }
        return new Registration(openTelemetry);
    }

    public static final class Registration implements AutoCloseable {
        private final OpenTelemetry installed;
        private boolean closed;

        private Registration(OpenTelemetry installed) {
            this.installed = installed;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            INSTALLED.compareAndSet(installed, null);
        }
    }
}
