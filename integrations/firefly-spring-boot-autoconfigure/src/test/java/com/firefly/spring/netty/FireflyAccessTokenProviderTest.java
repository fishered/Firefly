package com.firefly.spring.netty;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FireflyAccessTokenProviderTest {
    @Test
    void cachesAndRefreshesClientCredentialsTokenBeforeExpiry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/token", exchange -> {
            int request = requests.incrementAndGet();
            byte[] body = ("{\"accessToken\":\"token-" + request + "\",\"expiresIn\":60}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            FireflyNettyExecutorProperties.Auth auth = new FireflyNettyExecutorProperties.Auth();
            auth.setTokenUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/auth/token");
            auth.setClientId("billing");
            auth.setClientSecret("secret");
            MutableClock clock = new MutableClock(Instant.parse("2026-07-21T08:00:00Z"));
            FireflyAccessTokenProvider provider = new FireflyAccessTokenProvider(auth, "", clock);

            assertEquals("token-1", provider.accessToken());
            assertEquals("token-1", provider.accessToken());
            assertEquals(1, requests.get());

            clock.now.set(clock.instant().plusSeconds(31));
            assertEquals("token-2", provider.accessToken());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
