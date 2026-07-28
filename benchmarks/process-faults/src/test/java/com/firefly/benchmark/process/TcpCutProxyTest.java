package com.firefly.benchmark.process;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TcpCutProxyTest {
    @Test
    void forwardsTrafficAndCanRejectNewConnectionsDuringPartition() throws Exception {
        try (EchoServer echo = new EchoServer();
             TcpCutProxy proxy = new TcpCutProxy("127.0.0.1", echo.port())) {
            assertEquals("pong", exchange(proxy.port(), "ping"));

            proxy.partition();
            boolean rejected = awaitRejected(proxy.port(), Duration.ofSeconds(3));
            assertTrue(rejected);

            proxy.heal();
            assertEquals("pong", exchange(proxy.port(), "ping"));
        }
    }

    private static String exchange(int port, String message) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8
             ));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            writer.println(message);
            return reader.readLine();
        }
    }

    private static boolean awaitRejected(int port, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                String response = exchange(port, "ping");
                if (response == null) {
                    return true;
                }
            } catch (IOException expected) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile boolean running = true;

        private EchoServer() throws IOException, InterruptedException {
            serverSocket = new ServerSocket(0);
            thread = Thread.ofVirtual().start(this::run);
            assertTrue(started.await(1, TimeUnit.SECONDS));
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            running = false;
            serverSocket.close();
            thread.join(Duration.ofSeconds(2));
        }

        private void run() {
            started.countDown();
            while (running) {
                try (Socket socket = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(
                             socket.getInputStream(), StandardCharsets.UTF_8
                     ));
                     PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    String line = reader.readLine();
                    if ("ping".equals(line)) {
                        writer.println("pong");
                    }
                } catch (IOException e) {
                    if (running) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
    }
}
