package com.firefly.api.admin.http;

import static com.firefly.api.admin.http.routing.AdminRoutePolicies.ADMIN_ONLY;
import static com.firefly.api.admin.http.routing.AdminRoutePolicies.EXECUTIONS;
import static com.firefly.api.admin.http.routing.AdminRoutePolicies.JOBS;
import static com.firefly.api.admin.http.routing.AdminRoutePolicies.OUTBOX;
import static com.firefly.api.admin.http.routing.AdminRoutePolicies.PASSWORD_CHANGE;
import static com.firefly.api.admin.http.routing.AdminRoutePolicies.PUBLIC;
import static com.firefly.api.admin.http.routing.AdminRoutePolicies.STANDARD;

/** Single registration point for Admin endpoint handlers and their access policies. */
final class AdminRouteRegistration {
    private AdminRouteRegistration() {
    }

    static void register(
            AdminHttpRouter router,
            AdminSystemController system,
            AdminAuthController auth,
            AdminScheduleController schedules,
            AdminJobController jobs,
            AdminExecutionController executions,
            AdminClusterController cluster
    ) {
        router.route("/", system::index, ADMIN_ONLY)
                .route("/api/health", system::health, PUBLIC)
                .route("/api/auth/config", auth::config, PUBLIC)
                .route("/api/auth/login", auth::login, PUBLIC)
                .route("/api/auth/password", auth::passwordChange, PASSWORD_CHANGE)
                .route("/api/integration-key", auth::integrationKey, ADMIN_ONLY)
                .route("/api/plugins", system::plugins, STANDARD)
                .route("/api/schedules/preview", schedules::preview, STANDARD)
                .route("/api/schedules/timezones", schedules::timezones, STANDARD)
                .route("/api/overview", cluster::overview, STANDARD)
                .route("/api/jobs", jobs::jobs, JOBS)
                .route("/api/users", auth::users, ADMIN_ONLY)
                .route("/api/executions", executions::executions, EXECUTIONS)
                .route("/api/triggers", executions::triggers, EXECUTIONS)
                .route("/api/backfills", executions::backfills, EXECUTIONS)
                .route("/api/batches", executions::batches, EXECUTIONS)
                .route("/api/calendars", executions::calendars, JOBS)
                .route("/api/outbox", executions::outbox, OUTBOX)
                .route("/api/executors", cluster::executors, STANDARD)
                .route("/api/executor-definitions", cluster::executorDefinitions, STANDARD)
                .route("/api/nodes", cluster::nodes, STANDARD)
                .route("/api/audit", system::audit, STANDARD);
    }
}
