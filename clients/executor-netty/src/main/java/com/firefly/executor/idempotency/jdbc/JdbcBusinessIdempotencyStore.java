package com.firefly.executor.idempotency.jdbc;

import com.firefly.idempotency.FencedBusinessIdempotencyStore;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * JDBC business idempotency state machine backed by transactional row locking.
 *
 * <p>Claim tokens are monotonically increasing generations. Completion and release
 * are conditional state transitions, so an owner from an older generation cannot
 * mutate a claim after another process has recovered it.</p>
 */
public final class JdbcBusinessIdempotencyStore implements FencedBusinessIdempotencyStore {
    public static final String DEFAULT_TABLE = "firefly_executor_idempotency";

    private final Duration abandonedClaimTimeout;
    private final JdbcTransactionTemplate transactions;
    private final JdbcIdempotencyClaimDao claims;

    public JdbcBusinessIdempotencyStore(DataSource dataSource, Duration abandonedClaimTimeout) {
        this(dataSource, abandonedClaimTimeout, DEFAULT_TABLE);
    }

    public JdbcBusinessIdempotencyStore(
            DataSource dataSource, Duration abandonedClaimTimeout, String tableName
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.abandonedClaimTimeout = Objects.requireNonNull(abandonedClaimTimeout, "abandonedClaimTimeout");
        if (abandonedClaimTimeout.isZero() || abandonedClaimTimeout.isNegative()) {
            throw new IllegalArgumentException("abandonedClaimTimeout must be positive");
        }
        if (tableName == null || !tableName.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid idempotency table name");
        }
        this.transactions = new JdbcTransactionTemplate(dataSource);
        this.claims = new JdbcIdempotencyClaimDao(tableName);
    }

    @Override
    public Claim tryAcquireFenced(String key, Instant acquiredAt) {
        requireKey(key);
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return transactions.execute("acquire business idempotency claim", connection -> {
                    Instant now = claims.databaseNow(connection);
                    JdbcIdempotencyClaimDao.ClaimRow existing = claims.findForUpdate(connection, key);
                    if (existing == null) {
                        claims.insert(connection, key, 1L, now, now.plus(abandonedClaimTimeout));
                        return Claim.acquired(token(1L));
                    }
                    if (existing.status() == JdbcIdempotencyClaimDao.ClaimStatus.COMPLETED) {
                        return Claim.completed();
                    }
                    if (existing.status() == JdbcIdempotencyClaimDao.ClaimStatus.IN_PROGRESS
                            && existing.claimUntil().isAfter(now)) {
                        return Claim.inProgress();
                    }
                    long nextGeneration = Math.addExact(existing.generation(), 1L);
                    if (!claims.reclaim(
                            connection, key, existing.claimToken(), nextGeneration,
                            now, now.plus(abandonedClaimTimeout)
                    )) {
                        throw new IllegalStateException("idempotency claim changed while locked: " + key);
                    }
                    return Claim.acquired(token(nextGeneration));
                });
            } catch (JdbcOperationException concurrentInsert) {
                if (attempt == 0) continue;
                throw concurrentInsert;
            }
        }
        throw new IllegalStateException("failed to acquire business idempotency claim");
    }

    @Override
    public boolean markCompletedFenced(String key, String claimToken, Instant completedAt) {
        requireKey(key);
        requireToken(claimToken);
        Objects.requireNonNull(completedAt, "completedAt");
        return transactions.execute("complete business idempotency claim", connection -> {
            Instant now = claims.databaseNow(connection);
            return claims.complete(connection, key, claimToken, now);
        });
    }

    @Override
    public boolean releaseFenced(String key, String claimToken, Instant releasedAt, String errorMessage) {
        requireKey(key);
        requireToken(claimToken);
        Objects.requireNonNull(releasedAt, "releasedAt");
        return transactions.execute("release business idempotency claim", connection -> {
            Instant now = claims.databaseNow(connection);
            return claims.release(connection, key, claimToken, now, truncate(errorMessage));
        });
    }

    public int deleteTerminalBefore(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1) return 0;
        return transactions.execute("clean business idempotency records", connection -> {
            var keys = claims.findTerminalKeysBefore(connection, cutoff, limit);
            return claims.deleteTerminalKeysBefore(connection, keys, cutoff);
        });
    }

    private String token(long generation) {
        return Long.toString(generation);
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key must not be blank");
    }

    private void requireToken(String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("claim token must not be blank");
        }
    }

    private String truncate(String errorMessage) {
        if (errorMessage == null) return "";
        return errorMessage.length() <= 4000 ? errorMessage : errorMessage.substring(0, 4000);
    }
}
