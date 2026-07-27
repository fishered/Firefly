package com.firefly.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAdminUserRepositoryTest {
    @Test
    void initializesDefaultAdmin() {
        AdminUser admin = new InMemoryAdminUserRepository().find("admin").orElseThrow();

        assertTrue(new Pbkdf2PasswordHasher().verify("admin".toCharArray(), admin.passwordHash()));
        assertEquals(Set.of(FireflyRole.ADMIN), admin.roles());
        assertTrue(admin.enabled());
        assertTrue(admin.passwordChangeRequired());
    }
}
