package com.firefly.spring.job;

import com.firefly.domain.ExecutionContext;
import java.util.Map;

/** Friendly object facade for Spring handlers; hides protocol parameter names. */
public final class FireflyTaskContext {
    private final ExecutionContext execution;
    public FireflyTaskContext(ExecutionContext execution) { this.execution = java.util.Objects.requireNonNull(execution); }
    public ExecutionContext execution() { return execution; }
    public String executionId() { return execution.executionId(); }
    public String rootExecutionId() { return execution.rootExecutionId(); }
    public int attempt() { return execution.runAttempt(); }
    public String jobId() { return execution.jobId(); }
    public String parameter(String name) { return execution.parameters().get(name); }
    public Map<String,String> parameters() { return execution.parameters(); }
    public boolean sharded() { return parameter("firefly.shard.index") != null; }
    public int shardIndex() { return Integer.parseInt(java.util.Objects.requireNonNull(parameter("firefly.shard.index"), "not a sharded execution")); }
    public int shardTotal() { return Integer.parseInt(java.util.Objects.requireNonNull(parameter("firefly.shard.total"), "not a sharded execution")); }
}
