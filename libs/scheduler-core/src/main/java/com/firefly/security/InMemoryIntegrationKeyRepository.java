package com.firefly.security;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryIntegrationKeyRepository implements IntegrationKeyRepository {
    private final AtomicReference<IntegrationKeyRecord> record = new AtomicReference<>();

    @Override
    public Optional<IntegrationKeyRecord> find() {
        return Optional.ofNullable(record.get());
    }

    @Override
    public boolean create(IntegrationKeyRecord value) {
        return record.compareAndSet(null, value);
    }

    @Override
    public boolean update(IntegrationKeyRecord value, long expectedVersion) {
        while (true) {
            IntegrationKeyRecord current = record.get();
            if (current == null || current.version() != expectedVersion) return false;
            if (record.compareAndSet(current, value)) return true;
        }
    }
}
