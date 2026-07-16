package com.firefly.executor;

import java.util.List;

/** Immediate transport acceptance result. Final business results are reported asynchronously. */
public record RemoteDispatchResult(
        int requestedTargets,
        int acceptedTargets,
        List<String> targetInstanceIds
) {
    public RemoteDispatchResult {
        targetInstanceIds = List.copyOf(targetInstanceIds);
        if (requestedTargets < 0 || acceptedTargets < 0 || acceptedTargets > requestedTargets) {
            throw new IllegalArgumentException("invalid dispatch target counts");
        }
    }

    public static RemoteDispatchResult unavailable() {
        return new RemoteDispatchResult(0, 0, List.of());
    }

    public boolean accepted() {
        return requestedTargets > 0 && acceptedTargets == requestedTargets;
    }
}
