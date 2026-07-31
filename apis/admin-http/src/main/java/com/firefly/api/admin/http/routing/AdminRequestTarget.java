package com.firefly.api.admin.http.routing;

import java.util.Locale;
import java.util.Objects;

/** Normalized request coordinates relative to the matched Admin route. */
public record AdminRequestTarget(String method, String path, String routePath) {
    public AdminRequestTarget {
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        path = Objects.requireNonNull(path, "path");
        routePath = Objects.requireNonNull(routePath, "routePath");
    }

    public String relativePath() {
        if (path.equals(routePath)) return "";
        String routePrefix = routePath.endsWith("/") ? routePath : routePath + "/";
        if (!path.startsWith(routePrefix)) return null;
        return path.substring(routePath.length());
    }
}
