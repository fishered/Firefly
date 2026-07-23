package com.firefly.store.jdbc;

import com.firefly.security.IntegrationKeyService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcIntegrationKeyRepositoryTest {
    @Test
    void persistsOnlyTheDigestAcrossRepositoryInstances() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcSchema.initialize(dataSource, JdbcSchemaOptions.of("h2"));

        IntegrationKeyService first = new IntegrationKeyService(
                new JdbcIntegrationKeyRepository(dataSource), Clock.systemUTC()
        );
        String key = first.rotate().plaintext();

        IntegrationKeyService afterRestart = new IntegrationKeyService(
                new JdbcIntegrationKeyRepository(dataSource), Clock.systemUTC()
        );
        assertTrue(afterRestart.verify(key));
        assertFalse(new JdbcIntegrationKeyRepository(dataSource).find().orElseThrow().keyHash().contains(key));
    }
}
