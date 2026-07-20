package com.firefly.plugin;

@FunctionalInterface
public interface NodeDrainStatusProvider {
    NodeDrainStatus status(String nodeId);
}
