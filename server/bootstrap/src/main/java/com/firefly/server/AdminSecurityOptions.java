package com.firefly.server;

/** Bootstrap settings for the first persistent Admin console user. */
public record AdminSecurityOptions(String bootstrapUsername, String bootstrapPassword) {
    public static final String DEVELOPMENT_USERNAME = "admin";
    public static final String DEVELOPMENT_PASSWORD = "local-admin-secret";

    public AdminSecurityOptions {
        bootstrapUsername = bootstrapUsername == null ? "" : bootstrapUsername.trim();
        bootstrapPassword = bootstrapPassword == null ? "" : bootstrapPassword;
        if (bootstrapUsername.isBlank() != bootstrapPassword.isBlank()) {
            throw new IllegalArgumentException(
                    "Admin bootstrap username and password must either both be configured or both be empty"
            );
        }
        if (!bootstrapPassword.isBlank() && bootstrapPassword.length() < 8) {
            throw new IllegalArgumentException("Admin bootstrap password must contain at least 8 characters");
        }
    }

    public static AdminSecurityOptions disabled() {
        return new AdminSecurityOptions("", "");
    }

    public boolean bootstrapEnabled() {
        return !bootstrapUsername.isBlank();
    }

    public boolean usesDevelopmentCredentials() {
        return DEVELOPMENT_USERNAME.equals(bootstrapUsername)
                && DEVELOPMENT_PASSWORD.equals(bootstrapPassword);
    }
}
