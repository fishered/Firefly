package com.firefly.executor.idempotency.jdbc;

import com.firefly.idempotency.BusinessIdempotencyStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcBusinessIdempotencyStoreTest {
    @Test
    void completedBusinessKeyCannotExecuteAgain() throws Exception {
        JdbcBusinessIdempotencyStore store = store(Duration.ofMinutes(5));

        var first = store.tryAcquireFenced("order:1", Instant.now());
        assertEquals(BusinessIdempotencyStore.AcquireResult.ACQUIRED, first.result());
        assertEquals(BusinessIdempotencyStore.AcquireResult.IN_PROGRESS,
                store.tryAcquireFenced("order:1", Instant.now()).result());
        assertTrue(store.markCompletedFenced("order:1", first.claimToken(), Instant.now()));
        assertEquals(BusinessIdempotencyStore.AcquireResult.COMPLETED,
                store.tryAcquireFenced("order:1", Instant.now()).result());
        assertEquals(1, store.deleteTerminalBefore(Instant.now().plusSeconds(1), 100));
        assertEquals(BusinessIdempotencyStore.AcquireResult.ACQUIRED,
                store.tryAcquireFenced("order:1", Instant.now()).result());
    }

    @Test
    void releasedOrExpiredClaimGetsANewTokenAndFencesTheOldOwner() throws Exception {
        JdbcBusinessIdempotencyStore store = store(Duration.ofMillis(5));
        var first = store.tryAcquireFenced("order:2", Instant.now());
        assertTrue(store.releaseFenced("order:2", first.claimToken(), Instant.now(), "temporary"));

        var second = store.tryAcquireFenced("order:2", Instant.now());
        assertNotEquals(first.claimToken(), second.claimToken());
        assertFalse(store.markCompletedFenced("order:2", first.claimToken(), Instant.now()));
        assertTrue(store.markCompletedFenced("order:2", second.claimToken(), Instant.now()));

        var expiring = store.tryAcquireFenced("order:3", Instant.now());
        Thread.sleep(20);
        var recovered = store.tryAcquireFenced("order:3", Instant.now());
        assertNotEquals(expiring.claimToken(), recovered.claimToken());
        assertFalse(store.releaseFenced("order:3", expiring.claimToken(), Instant.now(), "late"));
    }

    private JdbcBusinessIdempotencyStore store(Duration timeout) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        String sql;
        try (var input = getClass().getResourceAsStream(
                "/com/firefly/executor/idempotency/jdbc/h2.sql"
        )) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
        }
        return new JdbcBusinessIdempotencyStore(dataSource, timeout);
    }
}
