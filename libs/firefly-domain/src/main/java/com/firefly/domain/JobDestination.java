package com.firefly.domain;

import java.util.Objects;

/** Transport-neutral destination for a job handler. */
public record JobDestination(JobDestinationType type, String executorName) {
    public JobDestination {
        Objects.requireNonNull(type, "type");
        executorName = executorName == null ? "" : executorName.trim();
        if (type == JobDestinationType.REMOTE_EXECUTOR && executorName.isBlank()) {
            throw new IllegalArgumentException("remote destination requires executorName");
        }
        if (type == JobDestinationType.LOCAL_HANDLER && !executorName.isBlank()) {
            throw new IllegalArgumentException("local destination must not define executorName");
        }
    }

    public static JobDestination local() {
        return new JobDestination(JobDestinationType.LOCAL_HANDLER, "");
    }

    public static JobDestination remote(String executorName) {
        return new JobDestination(JobDestinationType.REMOTE_EXECUTOR, executorName);
    }

    public boolean remote() {
        return type == JobDestinationType.REMOTE_EXECUTOR;
    }
}
