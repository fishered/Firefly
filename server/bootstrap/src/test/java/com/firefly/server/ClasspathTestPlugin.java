package com.firefly.server;

import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginContext;

import java.util.concurrent.atomic.AtomicInteger;

public final class ClasspathTestPlugin implements FireflyPlugin {
    static final AtomicInteger STARTED = new AtomicInteger();
    static final AtomicInteger CLOSED = new AtomicInteger();
    static volatile String configuredValue;

    @Override
    public String id() {
        return "classpath-test";
    }

    @Override
    public void start(FireflyPluginContext context) {
        configuredValue = context.configuration().pluginProperty(id(), "value", "missing");
        STARTED.incrementAndGet();
    }

    @Override
    public void close() {
        CLOSED.incrementAndGet();
    }
}
