package com.firefly.plugin;

import java.util.Objects;

/** Plugin declaration for host version, wire protocol, database schema, and rolling-upgrade support. */
public record FireflyPluginRuntimeCompatibility(
        FireflyPluginCompatibility pluginApi,
        String minimumFireflyVersion,
        String maximumFireflyVersion,
        int minimumExecutorProtocol,
        int maximumExecutorProtocol,
        int minimumDatabaseSchema,
        int maximumDatabaseSchema,
        boolean rollingUpgradeSafe
) {
    public FireflyPluginRuntimeCompatibility {
        pluginApi = Objects.requireNonNull(pluginApi, "pluginApi");
        minimumFireflyVersion = Objects.requireNonNull(minimumFireflyVersion, "minimumFireflyVersion");
        maximumFireflyVersion = Objects.requireNonNull(maximumFireflyVersion, "maximumFireflyVersion");
        if (minimumFireflyVersion.isBlank() || maximumFireflyVersion.isBlank()) {
            throw new IllegalArgumentException("Firefly version bounds must not be blank");
        }
        if (minimumExecutorProtocol < 1 || maximumExecutorProtocol < minimumExecutorProtocol) {
            throw new IllegalArgumentException("invalid executor protocol range");
        }
        if (minimumDatabaseSchema < 1 || maximumDatabaseSchema < minimumDatabaseSchema) {
            throw new IllegalArgumentException("invalid database schema range");
        }
    }

    public static FireflyPluginRuntimeCompatibility current(FireflyPluginCompatibility api) {
        return new FireflyPluginRuntimeCompatibility(api, "0.0.0", "999.999.999",
                1, Integer.MAX_VALUE, 1, Integer.MAX_VALUE, true);
    }

    public boolean supports(FireflyHostCompatibility host) {
        return pluginApi.supports(host.pluginApiLevel())
                && versionBetween(host.fireflyVersion(), minimumFireflyVersion, maximumFireflyVersion)
                && rangesOverlap(minimumExecutorProtocol, maximumExecutorProtocol,
                host.minimumExecutorProtocol(), host.maximumExecutorProtocol())
                && rangesOverlap(minimumDatabaseSchema, maximumDatabaseSchema,
                host.minimumDatabaseSchema(), host.maximumDatabaseSchema());
    }

    private static boolean rangesOverlap(int leftMin, int leftMax, int rightMin, int rightMax) {
        return leftMin <= rightMax && rightMin <= leftMax;
    }

    static boolean versionBetween(String value, String minimum, String maximum) {
        return compareVersions(value, minimum) >= 0 && compareVersions(value, maximum) <= 0;
    }

    static int compareVersions(String left, String right) {
        int[] a = parts(left);
        int[] b = parts(right);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    private static int[] parts(String version) {
        String[] raw = version.split("\\.");
        int[] result = new int[3];
        for (int index = 0; index < result.length && index < raw.length; index++) {
            String digits = raw[index].replaceAll("[^0-9].*$", "");
            result[index] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return result;
    }
}
