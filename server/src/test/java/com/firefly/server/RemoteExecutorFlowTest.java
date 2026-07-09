package com.firefly.server;

import com.firefly.executor.netty.NettyExecutorClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RemoteExecutorFlowTest {
    @Test
    void schedulesRemoteJobThroughAdminApiAndNettyExecutor() throws Exception {
        int adminPort = freePort();
        int gatewayPort = freePort();
        CountDownLatch fired = new CountDownLatch(1);

        FireflyBootstrap bootstrap = FireflyBootstrap.start(new ServerOptions(
                ServerNodeMode.STANDALONE,
                java.util.Set.of(ServerPlugin.ADMIN_WEB),
                false,
                true,
                adminPort,
                false,
                9711,
                true,
                gatewayPort
        ));
        try (bootstrap;
             NettyExecutorClient client = NettyExecutorClient.builder()
                     .schedulerHost("127.0.0.1")
                     .schedulerPort(gatewayPort)
                     .executorName("test-executor")
                     .serviceName("test-service")
                     .heartbeatInterval(Duration.ofSeconds(1))
                     .build()
                     .registerHandler("testHandler", context -> fired.countDown())) {
            client.start();
            Thread.sleep(300);

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + adminPort + "/api/jobs"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"id":"remote-flow-test","executorName":"test-executor","handlerName":"testHandler","cron":"*/1 * * * * *","zoneId":"UTC"}
                                    """))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(201, response.statusCode());
            assertTrue(fired.await(4, TimeUnit.SECONDS));
        }
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
