package com.firefly.executor.netty;

/** Bounded resource limits for the executor Gateway. */
public record NettyExecutorGatewayOptions(
        int resultQueueCapacity,
        int maxFrameLength,
        NettyTlsOptions tls,
        java.time.Duration tlsReloadInterval,
        String internalForwardHost,
        int internalForwardPort,
        String advertisedInternalAddress,
        String internalAuthToken,
        java.time.Duration instanceLocationRefreshInterval,
        java.time.Duration instanceLocationLease
) {
    public NettyExecutorGatewayOptions(int resultQueueCapacity, int maxFrameLength) {
        this(resultQueueCapacity, maxFrameLength, NettyTlsOptions.disabled(), java.time.Duration.ofSeconds(30));
    }

    public NettyExecutorGatewayOptions(
            int resultQueueCapacity, int maxFrameLength, NettyTlsOptions tls
    ) {
        this(resultQueueCapacity, maxFrameLength, tls, java.time.Duration.ofSeconds(30));
    }

    public NettyExecutorGatewayOptions(
            int resultQueueCapacity, int maxFrameLength, NettyTlsOptions tls,
            java.time.Duration tlsReloadInterval
    ) {
        this(resultQueueCapacity, maxFrameLength, tls, tlsReloadInterval,
                "127.0.0.1", 0, "", "", java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(90));
    }

    public NettyExecutorGatewayOptions {
        if (resultQueueCapacity < 1) {
            throw new IllegalArgumentException("resultQueueCapacity must be positive");
        }
        if (maxFrameLength < 1024) {
            throw new IllegalArgumentException("maxFrameLength must be at least 1024 bytes");
        }
        tls = java.util.Objects.requireNonNull(tls, "tls");
        tlsReloadInterval = java.util.Objects.requireNonNull(tlsReloadInterval, "tlsReloadInterval");
        if (tlsReloadInterval.isZero() || tlsReloadInterval.isNegative()) {
            throw new IllegalArgumentException("tlsReloadInterval must be positive");
        }
        internalForwardHost = java.util.Objects.requireNonNull(internalForwardHost, "internalForwardHost");
        advertisedInternalAddress = java.util.Objects.requireNonNull(
                advertisedInternalAddress, "advertisedInternalAddress"
        );
        internalAuthToken = internalAuthToken == null ? "" : internalAuthToken;
        instanceLocationRefreshInterval = java.util.Objects.requireNonNull(
                instanceLocationRefreshInterval, "instanceLocationRefreshInterval"
        );
        instanceLocationLease = java.util.Objects.requireNonNull(instanceLocationLease, "instanceLocationLease");
        if (internalForwardPort < 0 || internalForwardPort > 65535) {
            throw new IllegalArgumentException("internalForwardPort must be between 0 and 65535");
        }
        if (instanceLocationRefreshInterval.isZero() || instanceLocationRefreshInterval.isNegative()
                || instanceLocationLease.compareTo(instanceLocationRefreshInterval) <= 0) {
            throw new IllegalArgumentException("instance location lease must exceed its refresh interval");
        }
        if (internalForwardPort > 0 && advertisedInternalAddress.isBlank()) {
            advertisedInternalAddress = "http://" + internalForwardHost + ":" + internalForwardPort;
        }
        if (internalForwardPort > 0 && internalAuthToken.isBlank()) {
            throw new IllegalArgumentException("internalAuthToken is required when Gateway forwarding is enabled");
        }
        if (!advertisedInternalAddress.isBlank() && !advertisedInternalAddress.startsWith("http://")) {
            throw new IllegalArgumentException("advertisedInternalAddress must use http://");
        }
    }

    public static NettyExecutorGatewayOptions defaults() {
        return new NettyExecutorGatewayOptions(
                10_000, 64 * 1024, NettyTlsOptions.disabled(), java.time.Duration.ofSeconds(30)
        );
    }
}
