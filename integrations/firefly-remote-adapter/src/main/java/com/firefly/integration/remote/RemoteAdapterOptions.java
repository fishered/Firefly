package com.firefly.integration.remote;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Runtime configuration for a framework-neutral business-side adapter. */
public final class RemoteAdapterOptions {
    private final String schedulerHost;
    private final int schedulerPort;
    private final List<String> gatewayAddresses;
    private final String executorName;
    private final String instanceId;
    private final String serviceName;
    private final Duration startupTimeout;
    private final Duration heartbeatInterval;
    private final Duration reconnectInitialDelay;
    private final Duration reconnectMaxDelay;
    private final String integrationKey;
    private final Path idempotencyDirectory;
    private final Duration idempotencyRetention;
    private final RemoteAdapterTlsOptions tlsOptions;

    private RemoteAdapterOptions(Builder builder) {
        schedulerHost = requireNonBlank(builder.schedulerHost, "schedulerHost");
        schedulerPort = port(builder.schedulerPort, "schedulerPort");
        gatewayAddresses = List.copyOf(builder.gatewayAddresses);
        executorName = requireNonBlank(builder.executorName, "executorName");
        instanceId = requireNonBlank(builder.instanceId, "instanceId");
        serviceName = requireNonBlank(builder.serviceName, "serviceName");
        startupTimeout = positive(builder.startupTimeout, "startupTimeout");
        heartbeatInterval = positive(builder.heartbeatInterval, "heartbeatInterval");
        reconnectInitialDelay = nonNegative(builder.reconnectInitialDelay, "reconnectInitialDelay");
        reconnectMaxDelay = nonNegative(builder.reconnectMaxDelay, "reconnectMaxDelay");
        if (reconnectMaxDelay.compareTo(reconnectInitialDelay) < 0) {
            throw new IllegalArgumentException("reconnectMaxDelay must not be less than reconnectInitialDelay");
        }
        integrationKey = builder.integrationKey == null ? "" : builder.integrationKey;
        idempotencyDirectory = builder.idempotencyDirectory;
        idempotencyRetention = positive(builder.idempotencyRetention, "idempotencyRetention");
        tlsOptions = Objects.requireNonNull(builder.tlsOptions, "tlsOptions");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RemoteAdapterOptions fromEnvironment() {
        Properties properties = new Properties();
        try (var input = RemoteAdapterOptions.class.getClassLoader()
                .getResourceAsStream("firefly-remote-adapter.properties")) {
            if (input != null) properties.load(input);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load firefly-remote-adapter.properties", e);
        }
        System.getProperties().forEach((key, value) -> {
            if (String.valueOf(key).startsWith("firefly.executor.")) {
                properties.setProperty(String.valueOf(key), String.valueOf(value));
            }
        });
        String executorName = required(properties, "firefly.executor.name");
        return builder()
                .schedulerHost(value(properties, "firefly.executor.scheduler-host", "127.0.0.1"))
                .schedulerPort(integer(properties, "firefly.executor.scheduler-port", 9700))
                .gatewayAddresses(csv(properties, "firefly.executor.gateway-addresses"))
                .executorName(executorName)
                .instanceId(value(properties, "firefly.executor.instance-id", defaultInstanceId()))
                .serviceName(value(properties, "firefly.executor.service-name", executorName))
                .startupTimeout(duration(properties, "firefly.executor.startup-timeout", Duration.ofSeconds(30)))
                .heartbeatInterval(duration(properties, "firefly.executor.heartbeat-interval", Duration.ofSeconds(10)))
                .reconnectInitialDelay(duration(properties, "firefly.executor.reconnect-initial-delay", Duration.ofSeconds(1)))
                .reconnectMaxDelay(duration(properties, "firefly.executor.reconnect-max-delay", Duration.ofSeconds(30)))
                .integrationKey(value(properties, "firefly.executor.integration-key", ""))
                .idempotencyDirectory(path(properties, "firefly.executor.idempotency-directory"))
                .idempotencyRetention(duration(properties, "firefly.executor.idempotency-retention", Duration.ofHours(24)))
                .tlsOptions(tls(properties))
                .build();
    }

    public String schedulerHost() { return schedulerHost; }
    public int schedulerPort() { return schedulerPort; }
    public List<String> gatewayAddresses() { return gatewayAddresses; }
    public String executorName() { return executorName; }
    public String instanceId() { return instanceId; }
    public String serviceName() { return serviceName; }
    public Duration startupTimeout() { return startupTimeout; }
    public Duration heartbeatInterval() { return heartbeatInterval; }
    public Duration reconnectInitialDelay() { return reconnectInitialDelay; }
    public Duration reconnectMaxDelay() { return reconnectMaxDelay; }
    public String integrationKey() { return integrationKey; }
    public Path idempotencyDirectory() { return idempotencyDirectory; }
    public Duration idempotencyRetention() { return idempotencyRetention; }
    public RemoteAdapterTlsOptions tlsOptions() { return tlsOptions; }

    public static final class Builder {
        private String schedulerHost = "127.0.0.1";
        private int schedulerPort = 9700;
        private List<String> gatewayAddresses = new ArrayList<>();
        private String executorName = "";
        private String instanceId = defaultInstanceId();
        private String serviceName = "";
        private Duration startupTimeout = Duration.ofSeconds(30);
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration reconnectInitialDelay = Duration.ofSeconds(1);
        private Duration reconnectMaxDelay = Duration.ofSeconds(30);
        private String integrationKey = "";
        private Path idempotencyDirectory;
        private Duration idempotencyRetention = Duration.ofHours(24);
        private RemoteAdapterTlsOptions tlsOptions = RemoteAdapterTlsOptions.disabled();

        public Builder schedulerHost(String value) { schedulerHost = value; return this; }
        public Builder schedulerPort(int value) { schedulerPort = value; return this; }
        public Builder gatewayAddresses(List<String> value) { gatewayAddresses = value == null ? List.of() : List.copyOf(value); return this; }
        public Builder executorName(String value) { executorName = value; return this; }
        public Builder instanceId(String value) { instanceId = value; return this; }
        public Builder serviceName(String value) { serviceName = value; return this; }
        public Builder startupTimeout(Duration value) { startupTimeout = value; return this; }
        public Builder heartbeatInterval(Duration value) { heartbeatInterval = value; return this; }
        public Builder reconnectInitialDelay(Duration value) { reconnectInitialDelay = value; return this; }
        public Builder reconnectMaxDelay(Duration value) { reconnectMaxDelay = value; return this; }
        public Builder integrationKey(String value) { integrationKey = value; return this; }
        public Builder idempotencyDirectory(Path value) { idempotencyDirectory = value; return this; }
        public Builder idempotencyRetention(Duration value) { idempotencyRetention = value; return this; }
        public Builder tlsOptions(RemoteAdapterTlsOptions value) { tlsOptions = value; return this; }
        public RemoteAdapterOptions build() { return new RemoteAdapterOptions(this); }
    }

    private static RemoteAdapterTlsOptions tls(Properties properties) {
        boolean enabled = Boolean.parseBoolean(value(properties, "firefly.executor.tls-enabled", "false"));
        return new RemoteAdapterTlsOptions(
                enabled,
                path(properties, "firefly.executor.tls-certificate-chain"),
                path(properties, "firefly.executor.tls-private-key"),
                value(properties, "firefly.executor.tls-private-key-password", ""),
                path(properties, "firefly.executor.tls-trust-certificates"),
                Boolean.parseBoolean(value(properties, "firefly.executor.tls-verify-hostname", "true"))
        );
    }

    private static String required(Properties properties, String key) {
        return requireNonBlank(value(properties, key, ""), key);
    }

    private static String value(Properties properties, String key, String defaultValue) {
        String system = System.getProperty(key);
        if (system != null && !system.isBlank()) return system.trim();
        String environment = System.getenv(toEnvironmentKey(key));
        if (environment != null && !environment.isBlank()) return environment.trim();
        return properties.getProperty(key, defaultValue).trim();
    }

    private static List<String> csv(Properties properties, String key) {
        String value = value(properties, key, "");
        if (value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static int integer(Properties properties, String key, int defaultValue) {
        try { return Integer.parseInt(value(properties, key, Integer.toString(defaultValue))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer", e); }
    }

    private static Duration duration(Properties properties, String key, Duration defaultValue) {
        String value = value(properties, key, defaultValue.toString());
        try {
            if (value.matches("\\d+ms")) return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            if (value.matches("\\d+[smh]")) {
                long amount = Long.parseLong(value.substring(0, value.length() - 1));
                return switch (value.charAt(value.length() - 1)) {
                    case 's' -> Duration.ofSeconds(amount);
                    case 'm' -> Duration.ofMinutes(amount);
                    case 'h' -> Duration.ofHours(amount);
                    default -> throw new IllegalArgumentException();
                };
            }
            return Duration.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 or simple duration", e);
        }
    }

    private static Path path(Properties properties, String key) {
        String value = value(properties, key, "");
        return value.isBlank() ? null : Path.of(value);
    }

    private static String toEnvironmentKey(String key) {
        return key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static String defaultInstanceId() {
        String host = System.getenv("HOSTNAME");
        return host == null || host.isBlank() ? UUID.randomUUID().toString() : host;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static int port(int value, String name) {
        if (value < 1 || value > 65535) throw new IllegalArgumentException(name + " must be between 1 and 65535");
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
