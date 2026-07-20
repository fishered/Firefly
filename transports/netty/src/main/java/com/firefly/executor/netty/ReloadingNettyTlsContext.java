package com.firefly.executor.netty;

import io.netty.handler.ssl.SslContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reloads PEM material for new connections while existing TLS sessions drain naturally. */
final class ReloadingNettyTlsContext implements AutoCloseable {
    private static final Logger log = Logger.getLogger(ReloadingNettyTlsContext.class.getName());
    private final NettyTlsOptions options;
    private final AtomicReference<SslContext> current = new AtomicReference<>();
    private final ScheduledExecutorService watcher;
    private volatile String fingerprint;

    ReloadingNettyTlsContext(NettyTlsOptions options, Duration reloadInterval) {
        this.options = java.util.Objects.requireNonNull(options, "options");
        if (!options.enabled()) {
            watcher = null;
            return;
        }
        current.set(options.serverContext());
        fingerprint = fingerprint();
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "firefly-netty-tls-reloader");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleWithFixedDelay(
                this::reloadIfChanged,
                reloadInterval.toMillis(), reloadInterval.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    SslContext current() {
        return current.get();
    }

    void reloadIfChanged() {
        try {
            String next = fingerprint();
            if (next.equals(fingerprint)) return;
            current.set(options.serverContext());
            fingerprint = next;
            log.info("Netty Gateway TLS material reloaded for new connections");
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "failed to reload Netty Gateway TLS material; keeping previous context", e);
        }
    }

    private String fingerprint() {
        StringBuilder value = new StringBuilder();
        for (Path path : List.of(options.certificateChain(), options.privateKey())) append(value, path);
        if (options.trustCertificates() != null) append(value, options.trustCertificates());
        return value.toString();
    }

    private void append(StringBuilder value, Path path) {
        try {
            value.append(path.toAbsolutePath().normalize()).append(':')
                    .append(Files.size(path)).append(':')
                    .append(Files.getLastModifiedTime(path).toMillis()).append(';');
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to fingerprint TLS file: " + path, e);
        }
    }

    @Override
    public void close() {
        if (watcher != null) watcher.shutdownNow();
    }
}
