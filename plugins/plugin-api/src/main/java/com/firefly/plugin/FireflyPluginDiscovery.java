package com.firefly.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** Discovers classpath and external-JAR plugins through the JDK service-provider mechanism. */
public final class FireflyPluginDiscovery implements AutoCloseable {
    private final URLClassLoader externalClassLoader;
    private final List<FireflyPlugin> plugins;

    private FireflyPluginDiscovery(URLClassLoader externalClassLoader, List<FireflyPlugin> plugins) {
        this.externalClassLoader = externalClassLoader;
        this.plugins = List.copyOf(plugins);
    }

    public static FireflyPluginDiscovery discover(Path directory, ClassLoader parent) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(parent, "parent");
        URLClassLoader externalLoader = null;
        ClassLoader discoveryLoader = parent;
        List<Path> jars = pluginJars(directory);
        if (!jars.isEmpty()) {
            URL[] urls = jars.stream().map(FireflyPluginDiscovery::url).toArray(URL[]::new);
            externalLoader = new URLClassLoader("firefly-external-plugins", urls, parent);
            discoveryLoader = externalLoader;
        }
        try {
            List<FireflyPlugin> discovered = new ArrayList<>();
            ServiceLoader.load(FireflyPlugin.class, discoveryLoader).forEach(discovered::add);
            return new FireflyPluginDiscovery(externalLoader, discovered);
        } catch (ServiceConfigurationError | RuntimeException e) {
            closeQuietly(externalLoader);
            throw new IllegalStateException("failed to discover Firefly plugins from "
                    + directory.toAbsolutePath(), e);
        }
    }

    public List<FireflyPlugin> plugins() {
        return plugins;
    }

    @Override
    public void close() {
        closeQuietly(externalClassLoader);
    }

    private static List<Path> pluginJars(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("failed to list Firefly plugin directory: "
                    + directory.toAbsolutePath(), e);
        }
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("invalid plugin JAR path: " + path, e);
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) return;
        try {
            loader.close();
        } catch (IOException e) {
            throw new IllegalStateException("failed to close external plugin class loader", e);
        }
    }
}
