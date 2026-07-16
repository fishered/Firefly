package com.firefly.api.admin.model;

public record AdminJobView(
        String id,
        String name,
        String groupId,
        String handlerName,
        String executorName,
        String businessHandlerName,
        String schedule,
        String zoneId,
        boolean enabled,
        String misfirePolicy,
        String concurrencyPolicy,
        String nextFireTime
) {
}
