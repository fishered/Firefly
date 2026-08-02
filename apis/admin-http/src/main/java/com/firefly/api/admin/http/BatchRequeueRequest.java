package com.firefly.api.admin.http;

import java.util.List;

public record BatchRequeueRequest(List<String> outboxIds) {
    public BatchRequeueRequest {
        if (outboxIds == null || outboxIds.isEmpty()) throw new IllegalArgumentException("outboxIds must not be empty");
        outboxIds = outboxIds.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (outboxIds.isEmpty()) throw new IllegalArgumentException("outboxIds must not be empty");
    }
}
