package com.firefly.security;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAdminUserRepository implements AdminUserRepository {
    private final ConcurrentHashMap<String, AdminUser> users = new ConcurrentHashMap<>();

    @Override
    public Optional<AdminUser> find(String username) {
        return Optional.ofNullable(users.get(username));
    }

    @Override
    public List<AdminUser> list() {
        return users.values().stream().sorted(Comparator.comparing(AdminUser::username)).toList();
    }

    @Override
    public boolean create(AdminUser user) {
        return users.putIfAbsent(user.username(), user) == null;
    }

    @Override
    public boolean update(AdminUser user, long expectedVersion) {
        return users.computeIfPresent(user.username(), (key, current) ->
                current.version() == expectedVersion ? user : current) == user;
    }

    @Override
    public boolean delete(String username, long expectedVersion) {
        java.util.concurrent.atomic.AtomicBoolean deleted = new java.util.concurrent.atomic.AtomicBoolean();
        users.computeIfPresent(username, (key, current) -> {
            if (current.version() != expectedVersion) return current;
            deleted.set(true);
            return null;
        });
        return deleted.get();
    }
}
