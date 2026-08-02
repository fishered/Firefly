package com.firefly.server;

import java.util.HashMap;
import java.util.Map;

final class ServerFlagParser {
    private ServerFlagParser() {
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            String raw = arg.substring(2);
            if (raw.isBlank() || raw.startsWith("-") || raw.endsWith("=")) {
                throw new IllegalArgumentException("invalid server flag: " + arg);
            }
            int separator = raw.indexOf('=');
            String name = separator < 0 ? raw : raw.substring(0, separator);
            if (name.isBlank()) throw new IllegalArgumentException("invalid server flag: " + arg);
            if (flags.containsKey(name)) throw new IllegalArgumentException("duplicate server flag: " + name);
            if (separator < 0) {
                flags.put(name, "true");
            } else {
                flags.put(name, raw.substring(separator + 1));
            }
        }
        return Map.copyOf(flags);
    }
}
