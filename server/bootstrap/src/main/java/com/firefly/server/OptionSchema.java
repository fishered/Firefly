package com.firefly.server;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small registry used by bootstrap code to reject unknown core options. */
public final class OptionSchema {
    private final Map<String, OptionSpec<?>> specs = new LinkedHashMap<>();

    public OptionSchema register(OptionSpec<?> spec) {
        if (specs.putIfAbsent(spec.name(), spec) != null) {
            throw new IllegalArgumentException("duplicate option: " + spec.name());
        }
        return this;
    }

    public boolean contains(String name) {
        return specs.containsKey(name);
    }

    public Map<String, OptionSpec<?>> specs() {
        return Map.copyOf(specs);
    }
}
