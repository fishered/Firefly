package com.firefly.api.admin.http;

import com.sun.net.httpserver.HttpServer;

final class AdminHttpRouter {
    private final HttpServer server;
    private final AdminHttpDispatcher dispatcher;

    AdminHttpRouter(HttpServer server, AdminHttpDispatcher dispatcher) {
        this.server = java.util.Objects.requireNonNull(server, "server");
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher, "dispatcher");
    }

    AdminHttpRouter route(String path, AdminExchangeHandler handler) {
        server.createContext(path, exchange -> dispatcher.dispatch(exchange, handler));
        return this;
    }
}
