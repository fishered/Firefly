package com.firefly.store.jdbc;

import com.firefly.security.AdminUser;
import com.firefly.security.FireflyRole;
import com.firefly.security.Pbkdf2PasswordHasher;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAdminUserRepositoryTest {
    @Test
    void initializesDefaultAdminWithoutOverwritingIt() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        JdbcAdminUserRepository repository = new JdbcAdminUserRepository(dataSource);
        AdminUser admin = repository.find("admin").orElseThrow();
        assertTrue(new Pbkdf2PasswordHasher().verify("admin".toCharArray(), admin.passwordHash()));
        assertEquals(Set.of(FireflyRole.ADMIN), admin.roles());
        assertTrue(admin.passwordChangeRequired());

        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));
        AdminUser afterRepeatedInitialization = repository.find("admin").orElseThrow();
        assertEquals(admin.passwordHash(), afterRepeatedInitialization.passwordHash());
        assertTrue(afterRepeatedInitialization.passwordChangeRequired());
        assertEquals(admin.version(), afterRepeatedInitialization.version());
    }

    @Test
    void persistsUsersAndUsesVersionCompareAndSet() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));

        Instant createdAt = Instant.parse("2026-07-22T00:00:00Z");
        String hash = new Pbkdf2PasswordHasher().hash("correct-password".toCharArray());
        JdbcAdminUserRepository first = new JdbcAdminUserRepository(dataSource);
        assertTrue(first.create(new AdminUser(
                "operator", hash, Set.of(FireflyRole.ADMIN), true, 0, createdAt, createdAt
        )));
        assertFalse(first.create(new AdminUser(
                "operator", hash, Set.of(FireflyRole.ADMIN), true, 0, createdAt, createdAt
        )));

        JdbcAdminUserRepository afterRestart = new JdbcAdminUserRepository(dataSource);
        AdminUser persisted = afterRestart.find("operator").orElseThrow();
        assertTrue(new Pbkdf2PasswordHasher().verify("correct-password".toCharArray(), persisted.passwordHash()));
        assertEquals(Set.of(FireflyRole.ADMIN), persisted.roles());

        AdminUser updated = new AdminUser(
                "operator", persisted.passwordHash(), Set.of(FireflyRole.ADMIN), true, 1,
                persisted.createdAt(), createdAt.plusSeconds(30)
        );
        assertTrue(afterRestart.update(updated, 0));
        assertFalse(afterRestart.update(updated, 0));
        assertFalse(afterRestart.delete("operator", 0));
        assertTrue(afterRestart.delete("operator", 1));
    }
}
