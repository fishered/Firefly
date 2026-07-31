package com.firefly.api.admin.http.routing;

import com.firefly.api.admin.http.AdminRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRoutePolicyTest {
    @Test
    void limitsAnonymousAccessToTheExactRegisteredContext() {
        assertTrue(AdminRoutePolicies.PUBLIC.allowsAnonymous(
                new AdminRequestTarget("GET", "/health", "/health")
        ));
        assertFalse(AdminRoutePolicies.PUBLIC.allowsAnonymous(
                new AdminRequestTarget("GET", "/health/internal", "/health")
        ));
    }

    @Test
    void resolvesJobAccessWithoutDependingOnAbsoluteApiPaths() {
        assertEquals(AdminRole.READER, role(AdminRoutePolicies.JOBS, "GET", "/jobs", "/jobs"));
        assertEquals(AdminRole.OPERATOR, role(AdminRoutePolicies.JOBS, "PATCH", "/jobs/a", "/jobs"));
        assertEquals(AdminRole.OPERATOR, role(AdminRoutePolicies.JOBS, "POST", "/jobs/a/trigger", "/jobs"));
        assertEquals(AdminRole.ADMIN, role(AdminRoutePolicies.JOBS, "POST", "/jobs", "/jobs"));
    }

    @Test
    void limitsIntegrationKeysToJobSynchronizationOperations() {
        assertTrue(integration("GET", "/jobs/a"));
        assertTrue(integration("POST", "/jobs"));
        assertTrue(integration("PUT", "/jobs/a"));
        assertFalse(integration("GET", "/jobs"));
        assertFalse(integration("PUT", "/jobs/a/trigger"));
        assertFalse(integration("POST", "/jobs/a/trigger"));
    }

    @Test
    void resolvesExecutionAndOutboxOperationsByRelativeAction() {
        assertEquals(AdminRole.OPERATOR,
                role(AdminRoutePolicies.EXECUTIONS, "POST", "/executions/a/cancel", "/executions"));
        assertEquals(AdminRole.OPERATOR,
                role(AdminRoutePolicies.OUTBOX, "POST", "/outbox/a/requeue", "/outbox"));
        assertEquals(AdminRole.ADMIN,
                role(AdminRoutePolicies.OUTBOX, "POST", "/outbox/a/delete", "/outbox"));
    }

    private boolean integration(String method, String path) {
        return AdminRoutePolicies.JOBS.allowsIntegrationKey(
                new AdminRequestTarget(method, path, "/jobs")
        );
    }

    private AdminRole role(AdminRoutePolicy policy, String method, String path, String route) {
        return policy.requiredRole(new AdminRequestTarget(method, path, route));
    }
}
