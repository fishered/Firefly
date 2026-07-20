package com.firefly.executor;

import java.util.List;

public record ExecutorIsolationResult(
        int disconnectedInstances,
        int contactedGateways,
        int failedGateways,
        List<String> failedGatewayAddresses
) {
    public ExecutorIsolationResult {
        failedGatewayAddresses = List.copyOf(failedGatewayAddresses);
    }

    public static ExecutorIsolationResult local(int disconnectedInstances) {
        return new ExecutorIsolationResult(disconnectedInstances, 0, 0, List.of());
    }
}
