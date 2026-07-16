package com.firefly.engine;

import com.firefly.execution.ExecutionStatus;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Immediate acceptance plus asynchronous completion for one dispatcher submission. */
public record DispatchSubmission(
        boolean accepted,
        boolean remote,
        CompletionStage<ExecutionStatus> completion
) {
    public DispatchSubmission {
        Objects.requireNonNull(completion, "completion");
    }

    public static DispatchSubmission rejected(boolean remote) {
        return new DispatchSubmission(false, remote, CompletableFuture.completedFuture(ExecutionStatus.FAILED));
    }

    public static DispatchSubmission acceptedRemote() {
        return new DispatchSubmission(true, true, CompletableFuture.completedFuture(ExecutionStatus.DISPATCHED));
    }
}
