package com.firefly.plugin;

import java.util.List;
import java.util.Objects;

/**
 * Starts and stops a fixed set of plugins owned by the host application.
 */
public final class FireflyPluginManager implements AutoCloseable {
    private final List<FireflyPlugin> plugins;

    public FireflyPluginManager(List<FireflyPlugin> plugins) {
        this.plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
    }

    public void start(FireflyPluginContext context) {
        for (FireflyPlugin plugin : plugins) {
            plugin.start(context);
        }
    }

    @Override
    public void close() {
        for (int index = plugins.size() - 1; index >= 0; index--) {
            plugins.get(index).close();
        }
    }
}
