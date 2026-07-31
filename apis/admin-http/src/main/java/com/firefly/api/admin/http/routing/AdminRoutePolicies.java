package com.firefly.api.admin.http.routing;

import com.firefly.api.admin.http.AdminRole;

import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.ANY;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.BATCH_CANCEL;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.BATCH_REQUEUE;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.CANCEL;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.NON_ROOT;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.REQUEUE;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.ROOT;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.SINGLE_SEGMENT;
import static com.firefly.api.admin.http.routing.AdminRoutePolicy.PathMatch.TRIGGER;

/** Named policy catalog used by Admin route registration. */
public final class AdminRoutePolicies {
    public static final AdminRoutePolicy PUBLIC = AdminRoutePolicy.anonymous();
    public static final AdminRoutePolicy STANDARD = AdminRoutePolicy.standard();
    public static final AdminRoutePolicy ADMIN_ONLY = AdminRoutePolicy.builder(AdminRole.ADMIN).build();
    public static final AdminRoutePolicy PASSWORD_CHANGE = AdminRoutePolicy.builder(AdminRole.ADMIN)
            .allowPasswordChange("POST")
            .build();
    public static final AdminRoutePolicy JOBS = standardBuilder()
            .require(AdminRole.OPERATOR, "POST", TRIGGER)
            .allowIntegrationKey("GET", NON_ROOT)
            .allowIntegrationKey("HEAD", NON_ROOT)
            .allowIntegrationKey("POST", ROOT)
            .allowIntegrationKey("PUT", SINGLE_SEGMENT)
            .build();
    public static final AdminRoutePolicy EXECUTIONS = standardBuilder()
            .require(AdminRole.OPERATOR, "POST", CANCEL)
            .require(AdminRole.OPERATOR, "POST", BATCH_CANCEL)
            .build();
    public static final AdminRoutePolicy OUTBOX = standardBuilder()
            .require(AdminRole.OPERATOR, "POST", REQUEUE)
            .require(AdminRole.OPERATOR, "POST", BATCH_REQUEUE)
            .build();

    private AdminRoutePolicies() {
    }

    private static AdminRoutePolicy.Builder standardBuilder() {
        return AdminRoutePolicy.builder(AdminRole.ADMIN)
                .require(AdminRole.READER, "GET", ANY)
                .require(AdminRole.READER, "HEAD", ANY)
                .require(AdminRole.OPERATOR, "PATCH", ANY)
                .require(AdminRole.OPERATOR, "PUT", ANY);
    }
}
