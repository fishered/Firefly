package com.firefly.batch;

import java.util.List;

/** Deterministic splitter contract. Implementations must return stable boundaries for retries. */
public interface BatchPartitioner {
    List<BatchPartition> partition(BatchPartitionRequest request);

    record BatchPartitionRequest(String rootExecutionId, int totalShards, long inputSize,
                                 String partitionKey, java.util.Map<String, String> attributes) {
        public BatchPartitionRequest {
            if (rootExecutionId == null || rootExecutionId.isBlank() || totalShards < 1 || inputSize < 0
                    || partitionKey == null || partitionKey.isBlank()) throw new IllegalArgumentException("invalid batch partition request");
            attributes = java.util.Map.copyOf(attributes == null ? java.util.Map.of() : attributes);
        }
    }
}
