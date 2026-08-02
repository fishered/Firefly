package com.firefly.api.admin.http;

import java.util.List;

public record BatchCancelRequest(List<String> executionIds, String reason) {
    public BatchCancelRequest {
        if (executionIds == null || executionIds.isEmpty()) {
            throw new IllegalArgumentException("executionIds must not be empty");
        }
        executionIds = executionIds.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (executionIds.isEmpty()) throw new IllegalArgumentException("executionIds must not be empty");
        reason = reason == null || reason.isBlank() ? "cancelled by batch operator" : reason;
    }
}
