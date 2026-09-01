package com.firefly.batch;

import java.util.ArrayList;
import java.util.List;

/** Even, gap-free range partitioning for offset/limit based inputs. */
public final class RangeBatchPartitioner implements BatchPartitioner {
    @Override public List<BatchPartition> partition(BatchPartitionRequest request) {
        long base = request.inputSize() / request.totalShards();
        long remainder = request.inputSize() % request.totalShards();
        long offset = 0;
        List<BatchPartition> result = new ArrayList<>(request.totalShards());
        for (int shard = 0; shard < request.totalShards(); shard++) {
            long limit = base + (shard < remainder ? 1 : 0);
            result.add(new BatchPartition(shard, request.totalShards(), request.partitionKey(), offset, limit, request.attributes()));
            offset += limit;
        }
        return List.copyOf(result);
    }
}
