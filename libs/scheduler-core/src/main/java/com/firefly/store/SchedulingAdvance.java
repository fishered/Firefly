package com.firefly.store;

import com.firefly.engine.ExecutionCommand;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One atomic scheduler cursor advance and its associated dispatch commands. */
public record SchedulingAdvance(
        String jobId,
        Instant expectedCurrentNextFireTime,
        Instant nextFireTime,
        List<ExecutionCommand> commands
) {
    public SchedulingAdvance {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(expectedCurrentNextFireTime, "expectedCurrentNextFireTime");
        Objects.requireNonNull(nextFireTime, "nextFireTime");
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
    }
}
