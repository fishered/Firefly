package com.firefly.server;

/** Static token roles for the lightweight Admin API. */
public record AdminAuthorizationOptions(String readerToken, String operatorToken, String adminToken) {
    public AdminAuthorizationOptions {
        readerToken = normalize(readerToken);
        operatorToken = normalize(operatorToken);
        adminToken = normalize(adminToken);
    }

    public static AdminAuthorizationOptions defaults() {
        return new AdminAuthorizationOptions("", "", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
