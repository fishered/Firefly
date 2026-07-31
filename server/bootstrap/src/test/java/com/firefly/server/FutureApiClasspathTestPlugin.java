package com.firefly.server;

import com.firefly.plugin.FireflyPlugin;
import com.firefly.plugin.FireflyPluginCompatibility;
import com.firefly.plugin.FireflyPluginContext;

import java.util.concurrent.atomic.AtomicInteger;

public final class FutureApiClasspathTestPlugin implements FireflyPlugin {
    static final AtomicInteger STARTED = new AtomicInteger();

    @Override
    public String id() {
        return "future-api-test";
    }

    @Override
    public FireflyPluginCompatibility compatibility() {
        return new FireflyPluginCompatibility(2, 2);
    }

    @Override
    public void start(FireflyPluginContext context) {
        STARTED.incrementAndGet();
    }
}
