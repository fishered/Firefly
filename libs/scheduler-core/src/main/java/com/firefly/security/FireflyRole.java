package com.firefly.security;

/** Capabilities carried by a Firefly access token. */
public enum FireflyRole {
    EXECUTOR,
    READER,
    OPERATOR,
    ADMIN;

    public boolean allows(FireflyRole required) {
        if (this == ADMIN) return true;
        if (required == READER) return this == READER || this == OPERATOR;
        return this == required;
    }
}
