package com.firefly.benchmark.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpCutProxy implements AutoCloseable {
    private final String targetHost;
    private final int targetPort;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Thread> relays = new CopyOnWriteArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private volatile boolean partitioned;
    private volatile Duration latency = Duration.ZERO;

    public TcpCutProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.serverSocket = new ServerSocket(0);
        this.acceptThread = Thread.ofVirtual()
                .name("firefly-benchmark-tcp-proxy")
                .start(this::acceptLoop);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void partition() {
        partitioned = true;
        closeSockets();
    }

    public void heal() {
        partitioned = false;
    }

    public void latency(Duration latency) {
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must be >= 0");
        }
        this.latency = latency;
    }

    @Override
    public void close() throws IOException, InterruptedException {
        accepting.set(false);
        closeSockets();
        serverSocket.close();
        acceptThread.join(Duration.ofSeconds(2));
        for (Thread relay : relays) {
            relay.join(Duration.ofSeconds(1));
        }
    }

    private void acceptLoop() {
        while (accepting.get()) {
            try {
                Socket inbound = serverSocket.accept();
                if (partitioned) {
                    inbound.close();
                    continue;
                }
                Socket outbound = new Socket(targetHost, targetPort);
                sockets.add(inbound);
                sockets.add(outbound);
                relays.add(Thread.ofVirtual().start(() -> relay(inbound, outbound)));
                relays.add(Thread.ofVirtual().start(() -> relay(outbound, inbound)));
            } catch (IOException e) {
                if (accepting.get()) {
                    throw new IllegalStateException("tcp proxy accept loop failed", e);
                }
            }
        }
    }

    private void relay(Socket source, Socket target) {
        byte[] buffer = new byte[8192];
        try (InputStream input = source.getInputStream(); OutputStream output = target.getOutputStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (partitioned) {
                    break;
                }
                sleepLatency();
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(source);
            closeQuietly(target);
            sockets.remove(source);
            sockets.remove(target);
        }
    }

    private void sleepLatency() {
        Duration delay = latency;
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSockets() {
        for (Socket socket : sockets) {
            closeQuietly(socket);
        }
        sockets.clear();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
