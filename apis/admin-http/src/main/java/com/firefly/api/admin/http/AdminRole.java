package com.firefly.api.admin.http;

/** Hierarchical roles for the operational Admin API. */
public enum AdminRole {
    READER,
    OPERATOR,
    ADMIN;

    public boolean allows(AdminRole required) {
        return ordinal() >= required.ordinal();
    }
}
