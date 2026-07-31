package com.firefly.api.admin.http;

import com.firefly.api.admin.http.routing.AdminRoutePolicy;
import com.sun.net.httpserver.HttpServer;

final class AdminHttpRouter {
    private final HttpServer server;
    private final AdminHttpDispatcher dispatcher;

    AdminHttpRouter(HttpServer server, AdminHttpDispatcher dispatcher) {
        this.server = java.util.Objects.requireNonNull(server, "server");
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
    }

    AdminHttpRouter route(String path, AdminExchangeHandler handler, AdminRoutePolicy policy) {
        server.createContext(path, exchange -> dispatcher.dispatch(exchange, path, policy, handler));
        return this;
    }
}
