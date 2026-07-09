package com.firefly.server;

import java.util.Map;
import java.util.Objects;

/**
 * Parses lightweight server flags without introducing a configuration framework.
 */
public record ServerOptions(
        boolean demoEnabled,
        boolean adminWebEnabled,
        int adminWebPort,
        boolean prometheusMetricsEnabled,
        int prometheusMetricsPort
) {
    private static final int DEFAULT_ADMIN_WEB_PORT = 9710;
    private static final int DEFAULT_PROMETHEUS_METRICS_PORT = 9711;

    public ServerOptions {
        validatePort(adminWebPort, "adminWebPort");
        validatePort(prometheusMetricsPort, "prometheusMetricsPort");
    }

    public static ServerOptions parse(String[] args) {
        return parse(args, System.getenv());
    }

    static ServerOptions parse(String[] args, Map<String, String> env) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        Map<String, String> flags = ServerFlagParser.parse(args);
        return new ServerOptions(
                booleanOption(flags, env, "firefly.demo.enabled", "FIREFLY_DEMO_ENABLED", false),
                booleanOption(flags, env, "firefly.admin-web.enabled", "FIREFLY_ADMIN_WEB_ENABLED", false),
                intOption(flags, env, "firefly.admin-web.port", "FIREFLY_ADMIN_WEB_PORT", DEFAULT_ADMIN_WEB_PORT),
                booleanOption(flags, env, "firefly.metrics.prometheus.enabled", "FIREFLY_METRICS_PROMETHEUS_ENABLED", false),
                intOption(
                        flags,
                        env,
                        "firefly.metrics.prometheus.port",
                        "FIREFLY_METRICS_PROMETHEUS_PORT",
                        DEFAULT_PROMETHEUS_METRICS_PORT
                )
        );
    }

    private static boolean booleanOption(
            Map<String, String> flags,
            Map<String, String> env,
            String flagName,
            String envName,
            boolean defaultValue
    ) {
        String value = flags.getOrDefault(flagName, env.get(envName));
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int intOption(
            Map<String, String> flags,
            Map<String, String> env,
            String flagName,
            String envName,
            int defaultValue
    ) {
        String value = flags.getOrDefault(flagName, env.get(envName));
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    private static void validatePort(int port, String name) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(name + " must be between 1 and 65535");
        }
    }
}
