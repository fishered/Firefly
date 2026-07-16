package com.firefly.executor.netty;

/** Bounded resource limits for the executor Gateway. */
public record NettyExecutorGatewayOptions(
        int resultQueueCapacity, int maxFrameLength, NettyTlsOptions tls
) {
    public NettyExecutorGatewayOptions(int resultQueueCapacity, int maxFrameLength) {
        this(resultQueueCapacity, maxFrameLength, NettyTlsOptions.disabled());
    }

    public NettyExecutorGatewayOptions {
        if (resultQueueCapacity < 1) {
            throw new IllegalArgumentException("resultQueueCapacity must be positive");
        }
        if (maxFrameLength < 1024) {
            throw new IllegalArgumentException("maxFrameLength must be at least 1024 bytes");
        }
        tls = java.util.Objects.requireNonNull(tls, "tls");
    }

    public static NettyExecutorGatewayOptions defaults() {
        return new NettyExecutorGatewayOptions(10_000, 64 * 1024, NettyTlsOptions.disabled());
    }
}
