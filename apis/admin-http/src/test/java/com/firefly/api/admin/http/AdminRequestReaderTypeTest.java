package com.firefly.api.admin.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AdminRequestReaderTypeTest {
    private final AdminRequestReader reader = new AdminRequestReader(AdminRequestLimits.defaults());

    @Test
    void preservesIdsContainingCommas() {
        assertEquals(java.util.List.of("tenant,blue", "tenant-red"),
                reader.ids(Map.of("ids", "[\"tenant,blue\",\"tenant-red\"]"), "ids"));
    }

    @Test
    void rejectsTypoBooleanInsteadOfSilentlyDisabling() {
        assertThrows(IllegalArgumentException.class,
                () -> reader.booleanValue(Map.of("enabled", "treu"), "enabled", true));
    }

    @Test
    void bindsTypedBatchRequestAndRejectsUnknownFields() throws Exception {
        var request = reader.typedObject(exchange("{\"executionIds\":[\"a,b\"],\"reason\":\"manual\"}"), BatchCancelRequest.class);
        assertEquals(java.util.List.of("a,b"), request.executionIds());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> reader.typedObject(exchange("{\"executionIds\":[\"a\"],\"unknown\":1}"), BatchCancelRequest.class));
    }

    private static com.sun.net.httpserver.HttpExchange exchange(String body) {
        return new com.sun.net.httpserver.HttpExchange() {
            public java.net.URI getRequestURI() { return java.net.URI.create("/"); }
            public com.sun.net.httpserver.Headers getRequestHeaders() { return new com.sun.net.httpserver.Headers(); }
            public com.sun.net.httpserver.Headers getResponseHeaders() { return new com.sun.net.httpserver.Headers(); }
            public String getRequestMethod() { return "POST"; }
            public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
            public void close() { }
            public java.io.InputStream getRequestBody() { return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            public java.io.OutputStream getResponseBody() { return java.io.OutputStream.nullOutputStream(); }
            public void sendResponseHeaders(int r, long l) { }
            public java.net.InetSocketAddress getRemoteAddress() { return null; }
            public int getResponseCode() { return 200; }
            public java.net.InetSocketAddress getLocalAddress() { return null; }
            public String getProtocol() { return "HTTP/1.1"; }
            public Object getAttribute(String n) { return null; }
            public void setAttribute(String n, Object v) { }
            public void setStreams(java.io.InputStream i, java.io.OutputStream o) { }
            public javax.net.ssl.SSLSession getSSLSession() { return null; }
            public com.sun.net.httpserver.HttpPrincipal getPrincipal() { return null; }
        };
    }
}
