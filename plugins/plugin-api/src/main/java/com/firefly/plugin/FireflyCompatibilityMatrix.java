package com.firefly.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validates that every plugin and node in a rolling-upgrade window shares a usable contract. */
public final class FireflyCompatibilityMatrix {
    private FireflyCompatibilityMatrix() {
    }

    public static List<String> validate(
            List<FireflyHostCompatibility> nodes,
            List<FireflyPluginRuntimeCompatibility> plugins
    ) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(plugins, "plugins");
        if (nodes.isEmpty()) throw new IllegalArgumentException("compatibility matrix must contain nodes");
        List<String> failures = new ArrayList<>();
        for (FireflyHostCompatibility node : nodes) {
            for (FireflyPluginRuntimeCompatibility plugin : plugins) {
                if (!plugin.supports(node)) {
                    failures.add("plugin " + plugin + " is incompatible with Firefly " + node.fireflyVersion());
                }
            }
        }
        if (nodes.size() > 1) {
            plugins.stream().filter(plugin -> !plugin.rollingUpgradeSafe()).forEach(plugin ->
                    failures.add("plugin " + plugin + " is not declared rolling-upgrade safe"));
        }
        int protocolMin = nodes.stream().mapToInt(FireflyHostCompatibility::minimumExecutorProtocol).max().orElse(1);
        int protocolMax = nodes.stream().mapToInt(FireflyHostCompatibility::maximumExecutorProtocol).min().orElse(0);
        if (protocolMin > protocolMax) failures.add("nodes have no common executor protocol");
        int schemaMin = nodes.stream().mapToInt(FireflyHostCompatibility::minimumDatabaseSchema).max().orElse(1);
        int schemaMax = nodes.stream().mapToInt(FireflyHostCompatibility::maximumDatabaseSchema).min().orElse(0);
        if (schemaMin > schemaMax) failures.add("nodes have no common database schema");
        return List.copyOf(failures);
    }

    public static void requireValid(List<FireflyHostCompatibility> nodes,
                                    List<FireflyPluginRuntimeCompatibility> plugins) {
        List<String> failures = validate(nodes, plugins);
        if (!failures.isEmpty()) throw new IllegalArgumentException(String.join("; ", failures));
    }
}
