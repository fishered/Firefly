package com.firefly.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Persistence boundary for bounded, idempotent tenant capacity observations. */
public interface TenantCapacityStore {
    /** Returns true only when the flush id was accepted for the first time. */
    boolean merge(TenantCapacityFlush flush, Instant now);

    TenantCapacityBaseline baseline(String tenantId, Instant from, Instant to, Duration bucketWidth);

    default List<TenantCapacityBaseline> baselines(
            List<String> tenantIds, Instant from, Instant to, Duration bucketWidth
    ) {
        return tenantIds.stream().map(id -> baseline(id, from, to, bucketWidth)).toList();
    }

    /** Deletes at most {@code limit} oldest buckets and their dedupe rows. */
    int deleteBefore(Instant cutoff, int limit);
}
