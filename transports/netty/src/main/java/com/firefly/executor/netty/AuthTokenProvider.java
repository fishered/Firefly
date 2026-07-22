package com.firefly.executor.netty;

/** Supplies a current access token whenever an Executor establishes a Gateway session. */
@FunctionalInterface
public interface AuthTokenProvider {
    String accessToken();

    static AuthTokenProvider fixed(String token) {
        String value = token == null ? "" : token;
        return () -> value;
    }
}
