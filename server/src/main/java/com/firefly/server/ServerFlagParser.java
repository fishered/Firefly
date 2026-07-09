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
            int separator = raw.indexOf('=');
            if (separator < 0) {
                flags.put(raw, "true");
            } else {
                flags.put(raw.substring(0, separator), raw.substring(separator + 1));
            }
        }
        return Map.copyOf(flags);
    }
}
