package com.firefly.batch;

import java.util.Objects;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stable business-level outcome for a batch, beyond the transport execution status. */
public record BusinessResultSummary(String businessKey, long inputCount, long successCount,
                                    long failedCount, String checkpoint, String resultLocation,
                                    String checksum) {
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-fA-F]{64}");

    public BusinessResultSummary {
        if (businessKey == null || businessKey.isBlank()) throw new IllegalArgumentException("businessKey must not be blank");
        if (inputCount < 0 || successCount < 0 || failedCount < 0 || successCount + failedCount > inputCount) {
            throw new IllegalArgumentException("business result counts are invalid");
        }
        checkpoint = Objects.requireNonNullElse(checkpoint, "");
        resultLocation = Objects.requireNonNullElse(resultLocation, "");
        checksum = Objects.requireNonNullElse(checksum, "");
        if (successCount + failedCount > 0 && resultLocation.isBlank()) {
            throw new IllegalArgumentException("resultLocation is required for a non-empty result");
        }
        if (!checksum.isBlank() && !SHA_256.matcher(checksum).matches()) {
            throw new IllegalArgumentException("checksum must contain an exact 64-digit SHA-256 digest");
        }
        checksum = checksum.toLowerCase(Locale.ROOT);
    }

    public boolean complete() {
        return successCount + failedCount == inputCount;
    }
}
