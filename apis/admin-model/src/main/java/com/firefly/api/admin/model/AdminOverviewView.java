package com.firefly.api.admin.model;

import java.time.Instant;

public record AdminOverviewView(
        String status,
        int jobsTotal,
        int jobsEnabled,
        int jobsDisabled,
        int nodesOnline,
        int executorsOnline,
        Instant nextFireTime
) {
}
