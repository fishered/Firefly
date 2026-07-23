package com.firefly.plugin;

import java.util.List;
import java.util.Objects;

/**
 * Starts and stops a fixed set of plugins owned by the host application.
 */
public final class FireflyPluginManager implements AutoCloseable {
    private final List<FireflyPlugin> plugins;
    private final AutoCloseable discovery;
    private volatile int started;
    private volatile boolean active;
    private volatile boolean closed;

    public FireflyPluginManager(List<FireflyPlugin> plugins) {
        this(plugins, () -> { });
    }

    public FireflyPluginManager(List<FireflyPlugin> plugins, AutoCloseable discovery) {
        this.plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    public void start(FireflyPluginContext context) {
        Objects.requireNonNull(context, "context");
        if (closed) throw new IllegalStateException("plugin manager is closed");
        if (active) throw new IllegalStateException("plugins are already started");
        active = true;
        try {
            for (FireflyPlugin plugin : plugins) {
                plugin.start(context);
                started++;
            }
        } catch (RuntimeException | Error e) {
            closeStarted(e);
            throw e;
        }
    }

    public List<FireflyPluginDescriptor> descriptors() {
        int currentStarted = started;
        boolean currentClosed = closed;
        java.util.ArrayList<FireflyPluginDescriptor> result = new java.util.ArrayList<>(plugins.size());
        for (int index = 0; index < plugins.size(); index++) {
            FireflyPlugin plugin = plugins.get(index);
            result.add(new FireflyPluginDescriptor(
                    plugin.id(), plugin.displayName(), plugin.version(), plugin.description(),
                    plugin.getClass().getName(), source(plugin),
                    currentClosed ? "STOPPED" : index < currentStarted ? "ACTIVE" : "LOADED"
            ));
        }
        return List.copyOf(result);
    }

    private String source(FireflyPlugin plugin) {
        ClassLoader loader = plugin.getClass().getClassLoader();
        return loader instanceof java.net.URLClassLoader ? "EXTERNAL" : "CLASSPATH";
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (int index = started - 1; index >= 0; index--) {
            try {
                plugins.get(index).close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        started = 0;
        active = false;
        try {
            discovery.close();
        } catch (Exception e) {
            if (failure == null) failure = new IllegalStateException("failed to close plugin discovery", e);
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }

    private void closeStarted(Throwable startupFailure) {
        try {
            close();
        } catch (RuntimeException closeFailure) {
            startupFailure.addSuppressed(closeFailure);
        }
    }
}
