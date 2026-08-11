package com.firefly.plugin;

import java.util.List;
import java.util.Objects;

/**
 * Starts and stops a fixed set of plugins owned by the host application.
 */
public final class FireflyPluginManager implements AutoCloseable {
    private final List<FireflyPlugin> plugins;
    private final AutoCloseable discovery;
    private final FireflyHostCompatibility hostCompatibility;
    private volatile int started;
    private volatile boolean active;
    private volatile boolean closed;

    public FireflyPluginManager(List<FireflyPlugin> plugins) {
        this(plugins, () -> { });
    }

    public FireflyPluginManager(List<FireflyPlugin> plugins, AutoCloseable discovery) {
        this(plugins, discovery, FireflyHostCompatibility.current());
    }

    public FireflyPluginManager(List<FireflyPlugin> plugins, AutoCloseable discovery,
                                FireflyHostCompatibility hostCompatibility) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.hostCompatibility = Objects.requireNonNull(hostCompatibility, "hostCompatibility");
        try {
            this.plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
            this.plugins.forEach(this::requireCompatible);
        } catch (RuntimeException | Error failure) {
            closeDiscoveryAfterValidationFailure(failure);
            throw failure;
        }
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
                    currentClosed ? "STOPPED" : index < currentStarted ? "ACTIVE" : "LOADED",
                    plugin.runtimeCompatibility()
            ));
        }
        return List.copyOf(result);
    }

    private String source(FireflyPlugin plugin) {
        ClassLoader loader = plugin.getClass().getClassLoader();
        return loader instanceof java.net.URLClassLoader ? "EXTERNAL" : "CLASSPATH";
    }

    private void requireCompatible(FireflyPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        FireflyPluginRuntimeCompatibility runtime = Objects.requireNonNull(
                plugin.runtimeCompatibility(), "plugin runtime compatibility");
        FireflyPluginCompatibility compatibility = runtime.pluginApi();
        if (runtime.supports(hostCompatibility)) return;
        throw new IllegalArgumentException(
                "Firefly plugin '" + plugin.id() + "' supports API levels "
                        + compatibility.minimumApiLevel() + ".." + compatibility.maximumApiLevel()
                        + " but the host API level is " + hostCompatibility.pluginApiLevel()
                        + " (Firefly " + hostCompatibility.fireflyVersion()
                        + ", executor protocol " + hostCompatibility.minimumExecutorProtocol() + ".."
                        + hostCompatibility.maximumExecutorProtocol()
                        + ", database schema " + hostCompatibility.minimumDatabaseSchema() + ".."
                        + hostCompatibility.maximumDatabaseSchema() + ")"
        );
    }

    private void closeDiscoveryAfterValidationFailure(Throwable failure) {
        try {
            discovery.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
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
