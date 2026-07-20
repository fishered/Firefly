package com.firefly.executor.netty;

import java.util.Set;

/** Version and capability contract negotiated during executor registration. */
public final class NettyExecutorProtocol {
    public static final int CURRENT_VERSION = 2;
    public static final int MIN_SUPPORTED_VERSION = 1;
    public static final Set<String> SERVER_CAPABILITIES = Set.of(
            "TARGET_ACK", "RESULT_REPORT", "MULTI_GATEWAY", "FENCING_TOKEN",
            "CANCELLATION", "GATEWAY_FORWARDING"
    );
    public static final Set<String> CLIENT_CAPABILITIES = Set.of(
            "TARGET_ACK", "RESULT_REPORT", "COMPLETED_RESULT_REPLAY", "CANCELLATION"
    );
    public static final Set<String> REQUIRED_CAPABILITIES = Set.of("TARGET_ACK", "RESULT_REPORT");

    private NettyExecutorProtocol() {
    }

    public static boolean supports(int version) {
        return version >= MIN_SUPPORTED_VERSION && version <= CURRENT_VERSION;
    }

    public static String encodeCapabilities(Set<String> capabilities) {
        return capabilities.stream().sorted().collect(java.util.stream.Collectors.joining(","));
    }

    public static Set<String> decodeCapabilities(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(capability -> !capability.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static boolean hasRequiredCapabilities(Set<String> capabilities) {
        return capabilities.containsAll(REQUIRED_CAPABILITIES);
    }
}
