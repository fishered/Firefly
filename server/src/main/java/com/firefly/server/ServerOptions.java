package com.firefly.server;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Parses lightweight server flags without introducing a configuration framework.
 */
public record ServerOptions(
        ServerNodeMode nodeMode,
        Set<ServerPlugin> plugins,
        boolean demoEnabled,
        boolean adminWebEnabled,
        int adminWebPort,
        boolean prometheusMetricsEnabled,
        int prometheusMetricsPort,
        boolean nettyExecutorGatewayEnabled,
        int nettyExecutorGatewayPort
) {
    private static final int DEFAULT_ADMIN_WEB_PORT = 9710;
    private static final int DEFAULT_PROMETHEUS_METRICS_PORT = 9711;
    private static final int DEFAULT_NETTY_EXECUTOR_GATEWAY_PORT = 9700;

    public ServerOptions {
        Objects.requireNonNull(nodeMode, "nodeMode");
        plugins = Set.copyOf(Objects.requireNonNull(plugins, "plugins"));
        validatePort(adminWebPort, "adminWebPort");
        validatePort(prometheusMetricsPort, "prometheusMetricsPort");
        validatePort(nettyExecutorGatewayPort, "nettyExecutorGatewayPort");
    }

    public static ServerOptions parse(String[] args) {
        return parse(args, System.getenv());
    }

    static ServerOptions parse(String[] args, Map<String, String> env) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(env, "env");
        Map<String, String> flags = ServerFlagParser.parse(args);
        Set<ServerPlugin> plugins = pluginList(flags, env);
        boolean adminWebEnabled = plugins.contains(ServerPlugin.ADMIN_WEB)
                || booleanOption(flags, env, "firefly.admin-web.enabled", "FIREFLY_ADMIN_WEB_ENABLED", false);
        boolean prometheusMetricsEnabled = plugins.contains(ServerPlugin.METRICS_PROMETHEUS)
                || booleanOption(flags, env, "firefly.metrics.prometheus.enabled", "FIREFLY_METRICS_PROMETHEUS_ENABLED", false);
        return new ServerOptions(
                nodeMode(flags, env),
                plugins,
                booleanOption(flags, env, "firefly.demo.enabled", "FIREFLY_DEMO_ENABLED", false),
                adminWebEnabled,
                intOption(flags, env, "firefly.admin-web.port", "FIREFLY_ADMIN_WEB_PORT", DEFAULT_ADMIN_WEB_PORT),
                prometheusMetricsEnabled,
                intOption(
                        flags,
                        env,
                        "firefly.metrics.prometheus.port",
                        "FIREFLY_METRICS_PROMETHEUS_PORT",
                        DEFAULT_PROMETHEUS_METRICS_PORT
                ),
                booleanOption(
                        flags,
                        env,
                        "firefly.executor.gateway.netty.enabled",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_ENABLED",
                        false
                ),
                intOption(
                        flags,
                        env,
                        "firefly.executor.gateway.netty.port",
                        "FIREFLY_EXECUTOR_GATEWAY_NETTY_PORT",
                        DEFAULT_NETTY_EXECUTOR_GATEWAY_PORT
                )
        );
    }

    private static ServerNodeMode nodeMode(Map<String, String> flags, Map<String, String> env) {
        String value = flags.getOrDefault("firefly.node.mode", env.getOrDefault("FIREFLY_NODE_MODE", "standalone"));
        return ServerNodeMode.parse(value);
    }

    private static Set<ServerPlugin> pluginList(Map<String, String> flags, Map<String, String> env) {
        String value = flags.getOrDefault("firefly.plugins", env.get("FIREFLY_PLUGINS"));
        return ServerPlugin.parseList(value);
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
