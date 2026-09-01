package com.firefly.store;

import com.firefly.engine.ExecutionCommand;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Boundary for due-job cursors and the scheduler's atomic enqueue operation. */
public interface SchedulingStore {
    DueJobBatch findDueBatch(Instant now, int softLimit, int hardLimit, Set<String> excludedJobIds);
    boolean updateNextFireTime(String jobId, Instant expectedCurrentNextFireTime, Instant nextFireTime);
    boolean advanceAndEnqueue(String jobId, Instant expectedCurrentNextFireTime,
                              Instant nextFireTime, List<ExecutionCommand> commands);
}
