package com.firefly.api.admin.http.routing;

import com.firefly.api.admin.http.AdminRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Declarative authentication and role rules attached to one registered route. */
public final class AdminRoutePolicy {
    private final boolean anonymous;
    private final AdminRole defaultRole;
    private final List<RoleRule> roleRules;
    private final List<MatchRule> integrationRules;
    private final List<MatchRule> passwordChangeRules;

    private AdminRoutePolicy(Builder builder) {
        this.anonymous = builder.anonymous;
        this.defaultRole = builder.defaultRole;
        this.roleRules = List.copyOf(builder.roleRules);
        this.integrationRules = List.copyOf(builder.integrationRules);
        this.passwordChangeRules = List.copyOf(builder.passwordChangeRules);
    }

    public boolean allowsAnonymous(AdminRequestTarget target) {
        return anonymous && "".equals(target.relativePath());
    }

    public AdminRole requiredRole(AdminRequestTarget target) {
        return roleRules.stream()
                .filter(rule -> rule.matcher().matches(target))
                .map(RoleRule::role)
                .findFirst()
                .orElse(defaultRole);
    }

    public boolean allowsIntegrationKey(AdminRequestTarget target) {
        return integrationRules.stream().anyMatch(rule -> rule.matches(target));
    }

    public boolean allowsPasswordChange(AdminRequestTarget target) {
        return passwordChangeRules.stream().anyMatch(rule -> rule.matches(target));
    }

    public static Builder builder(AdminRole defaultRole) {
        return new Builder(defaultRole);
    }

    public static AdminRoutePolicy anonymous() {
        return builder(AdminRole.ADMIN).anonymous().build();
    }

    public static AdminRoutePolicy standard() {
        return builder(AdminRole.ADMIN)
                .require(AdminRole.READER, "GET", PathMatch.ANY)
                .require(AdminRole.READER, "HEAD", PathMatch.ANY)
                .require(AdminRole.OPERATOR, "PATCH", PathMatch.ANY)
                .require(AdminRole.OPERATOR, "PUT", PathMatch.ANY)
                .build();
    }

    public static final class Builder {
        private final AdminRole defaultRole;
        private final List<RoleRule> roleRules = new ArrayList<>();
        private final List<MatchRule> integrationRules = new ArrayList<>();
        private final List<MatchRule> passwordChangeRules = new ArrayList<>();
        private boolean anonymous;

        private Builder(AdminRole defaultRole) {
            this.defaultRole = Objects.requireNonNull(defaultRole, "defaultRole");
        }

        public Builder anonymous() {
            this.anonymous = true;
            return this;
        }

        public Builder require(AdminRole role, String method, PathMatch path) {
            roleRules.add(new RoleRule(new MatchRule(method, path), Objects.requireNonNull(role, "role")));
            return this;
        }

        public Builder allowIntegrationKey(String method, PathMatch path) {
            integrationRules.add(new MatchRule(method, path));
            return this;
        }

        public Builder allowPasswordChange(String method) {
            passwordChangeRules.add(new MatchRule(method, PathMatch.ROOT));
            return this;
        }

        public AdminRoutePolicy build() {
            return new AdminRoutePolicy(this);
        }
    }

    public enum PathMatch {
        ANY {
            @Override boolean matches(String relativePath) { return relativePath != null; }
        },
        ROOT {
            @Override boolean matches(String relativePath) { return "".equals(relativePath); }
        },
        NON_ROOT {
            @Override boolean matches(String relativePath) { return relativePath != null && !relativePath.isEmpty(); }
        },
        SINGLE_SEGMENT {
            @Override boolean matches(String relativePath) {
                return relativePath != null && relativePath.startsWith("/")
                        && relativePath.indexOf('/', 1) < 0;
            }
        },
        TRIGGER {
            @Override boolean matches(String relativePath) { return endsWith(relativePath, "/trigger"); }
        },
        CANCEL {
            @Override boolean matches(String relativePath) { return endsWith(relativePath, "/cancel"); }
        },
        BATCH_CANCEL {
            @Override boolean matches(String relativePath) { return "/batch-cancel".equals(relativePath); }
        },
        REQUEUE {
            @Override boolean matches(String relativePath) { return endsWith(relativePath, "/requeue"); }
        },
        BATCH_REQUEUE {
            @Override boolean matches(String relativePath) { return "/batch-requeue".equals(relativePath); }
        };

        abstract boolean matches(String relativePath);

        private static boolean endsWith(String value, String suffix) {
            return value != null && value.endsWith(suffix);
        }
    }

    private record MatchRule(String method, PathMatch path) {
        private MatchRule {
            method = Objects.requireNonNull(method, "method").toUpperCase(java.util.Locale.ROOT);
            Objects.requireNonNull(path, "path");
        }

        boolean matches(AdminRequestTarget target) {
            return method.equals(target.method()) && path.matches(target.relativePath());
        }
    }

    private record RoleRule(MatchRule matcher, AdminRole role) {
    }
}
