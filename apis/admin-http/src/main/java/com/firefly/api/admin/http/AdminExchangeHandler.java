package com.firefly.api.admin.http;

import com.sun.net.httpserver.HttpExchange;

@FunctionalInterface
interface AdminExchangeHandler {
    void handle(HttpExchange exchange) throws Exception;
}
