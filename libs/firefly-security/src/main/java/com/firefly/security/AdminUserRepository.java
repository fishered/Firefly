package com.firefly.security;

import java.util.List;
import java.util.Optional;

public interface AdminUserRepository {
    Optional<AdminUser> find(String username);

    List<AdminUser> list();

    boolean create(AdminUser user);

    boolean update(AdminUser user, long expectedVersion);

    boolean delete(String username, long expectedVersion);
}
