package com.firefly.integration.remote;

import com.firefly.handler.JobHandler;
import com.firefly.executor.netty.NettyExecutorClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-neutral registry for the handler names advertised by a remote
 * executor. It deliberately contains no job schedule or control-plane API.
 */
public final class RemoteHandlerRegistry {
    private final Map<String, JobHandler> handlers = new LinkedHashMap<>();

    public RemoteHandlerRegistry bind(String name, JobHandler handler) {
        String actualName = requireName(name);
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(actualName, handler) != null) {
            throw new IllegalArgumentException("duplicate remote handler: " + actualName);
        }
        return this;
    }

    public Set<String> names() {
        return Set.copyOf(handlers.keySet());
    }

    void registerInto(NettyExecutorClient client) {
        handlers.forEach(client::registerHandler);
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        if (value.isBlank() || value.contains(",")) {
            throw new IllegalArgumentException("handler name must not be blank or contain ','");
        }
        return value;
    }
}
