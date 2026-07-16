package com.firefly.server;

import java.util.Locale;

public enum ServerNodeMode {
    STANDALONE,
    CLUSTER;

    public static ServerNodeMode parse(String value) {
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
