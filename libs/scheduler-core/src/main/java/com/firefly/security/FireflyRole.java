package com.firefly.security;

/** Admin console capabilities carried by a human user access token. */
public enum FireflyRole {
    READER,
    OPERATOR,
    ADMIN;

    public boolean allows(FireflyRole required) {
        if (this == ADMIN) return true;
        if (required == READER) return this == READER || this == OPERATOR;
        return this == required;
    }
}
