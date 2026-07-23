package com.firefly.security;

import java.util.Optional;

/** Stores the cluster-wide Integration Key digest without ever persisting its plaintext value. */
public interface IntegrationKeyRepository {
    Optional<IntegrationKeyRecord> find();

    boolean create(IntegrationKeyRecord record);

    boolean update(IntegrationKeyRecord record, long expectedVersion);
}
