package com.firefly.executor.netty;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Durable local result store suitable for an executor container with a mounted volume. */
public final class FileExecutorResultStore implements ExecutorResultStore {
    private static final Logger log = Logger.getLogger(FileExecutorResultStore.class.getName());
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_STRING_BYTES = 1024 * 1024;

    private final Path directory;
    private final Duration retention;
    private final Clock clock;
    private final AtomicLong nextCleanupAt = new AtomicLong();

    public FileExecutorResultStore(Path directory, Duration retention) {
        this(directory, retention, Clock.systemUTC());
    }

    FileExecutorResultStore(Path directory, Duration retention, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        try {
            Files.createDirectories(this.directory);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create executor result directory", e);
        }
    }

    @Override
    public Optional<ExecutorExecutionResult> find(String executionId) {
        Path file = resultFile(executionId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != FORMAT_VERSION) return discard(file);
            long expiresAt = input.readLong();
            String storedExecutionId = readString(input);
            String status = readString(input);
            String errorMessage = readString(input);
            if (!storedExecutionId.equals(executionId)) return discard(file);
            if (expiresAt <= clock.millis()) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            return Optional.of(new ExecutorExecutionResult(status, errorMessage));
        } catch (CorruptResultException | java.io.EOFException e) {
            return discard(file);
        } catch (IOException | RuntimeException e) {
            throw new UncheckedIOException("failed to read executor result: " + executionId, asIOException(e));
        }
    }

    @Override
    public void save(String executionId, ExecutorExecutionResult result) {
        Path target = resultFile(executionId);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".firefly-result-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                 DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel))) {
                output.writeInt(FORMAT_VERSION);
                output.writeLong(clock.instant().plus(retention).toEpochMilli());
                writeString(output, executionId);
                writeString(output, result.status());
                writeString(output, result.errorMessage());
                output.flush();
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            cleanupIfDue();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist executor result: " + executionId, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The target result is already durable; stale temp cleanup is best effort.
                }
            }
        }
    }

    private void cleanupIfDue() {
        long now = clock.millis();
        long scheduled = nextCleanupAt.get();
        if (scheduled > now) return;
        long interval = Math.max(1_000, Math.min(retentionMillis(), Duration.ofHours(1).toMillis()));
        if (!nextCleanupAt.compareAndSet(scheduled, now + interval)) return;
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".result"))
                    .filter(path -> expiredOrCorrupt(path, now))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.log(Level.FINE, "failed to delete expired executor result " + path, e);
                        }
                    });
        } catch (IOException e) {
            log.log(Level.WARNING, "failed to clean executor result directory " + directory, e);
        }
    }

    private boolean expiredOrCorrupt(Path path, long now) {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            return input.readInt() != FORMAT_VERSION || input.readLong() <= now;
        } catch (IOException e) {
            return true;
        }
    }

    private long retentionMillis() {
        try {
            return retention.toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private Path resultFile(String executionId) {
        Objects.requireNonNull(executionId, "executionId");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(executionId.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".result");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("executor result field exceeds " + MAX_STRING_BYTES + " bytes");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new CorruptResultException("invalid executor result field length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new CorruptResultException("truncated executor result field");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Optional<ExecutorExecutionResult> discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.log(Level.FINE, "failed to delete corrupt executor result " + file, e);
        }
        return Optional.empty();
    }

    private IOException asIOException(Exception exception) {
        return exception instanceof IOException io ? io : new IOException(exception);
    }

    private static final class CorruptResultException extends IOException {
        private CorruptResultException(String message) {
            super(message);
        }
    }
}
