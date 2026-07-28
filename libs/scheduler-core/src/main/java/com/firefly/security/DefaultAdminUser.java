package com.firefly.security;

import java.time.Instant;
import java.util.Set;

/** Default administrator installed with a new storage repository. */
public final class DefaultAdminUser {
    public static final String USERNAME = "admin";
    public static final String PASSWORD_HASH =
            "pbkdf2-sha256$210000$cdNnTGyvKtyrY2J5VniRJw$fugVWfUlpN9f84Rkjagj5aBkaBGyWwJuy68TBfJCAe4";

    private DefaultAdminUser() {
    }

    public static AdminUser at(Instant now) {
        return new AdminUser(USERNAME, PASSWORD_HASH, Set.of(FireflyRole.ADMIN), true, true, 0, now, now);
    }
}
