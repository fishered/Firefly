package com.firefly.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Shared shutdown protocol: interrupt work, await a bound, then release ownership. */
public final class ManagedWorker {
    private ManagedWorker() {
    }

    public static boolean stop(
            ExecutorService executor,
            Duration timeout,
            Runnable releaseOwnership,
            Logger logger
    ) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(releaseOwnership, "releaseOwnership");
        Objects.requireNonNull(logger, "logger");
        executor.shutdownNow();
        boolean stopped = false;
        try {
            stopped = executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!stopped) logger.warning("managed worker did not stop within " + timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warning("interrupted while waiting for managed worker to stop");
        } finally {
            releaseOwnership.run();
        }
        return stopped;
    }
}
