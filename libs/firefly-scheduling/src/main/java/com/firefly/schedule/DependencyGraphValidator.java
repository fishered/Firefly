package com.firefly.schedule;

import java.util.*;

/** Rejects dependency cycles at configuration save time. */
public final class DependencyGraphValidator {
    public void validate(Collection<JobDependency> dependencies) {
        Map<String, Set<String>> graph = new HashMap<>();
        for (JobDependency dependency : dependencies) {
            graph.computeIfAbsent(dependency.jobId(), ignored -> new HashSet<>()).add(dependency.prerequisiteJobId());
            graph.computeIfAbsent(dependency.prerequisiteJobId(), ignored -> new HashSet<>());
        }
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String node : graph.keySet()) dfs(node, graph, visiting, visited);
    }

    private void dfs(String node, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) return;
        if (!visiting.add(node)) throw new IllegalArgumentException("dependency cycle detected at " + node);
        for (String next : graph.getOrDefault(node, Set.of())) dfs(next, graph, visiting, visited);
        visiting.remove(node); visited.add(node);
    }
}
