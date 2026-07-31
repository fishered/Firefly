package com.firefly.api.admin.http;

/** Resource limits applied at the Admin HTTP trust boundary. */
public record AdminRequestLimits(
        int maxRequestBodyBytes,
        int maxQueryLength,
        int maxJsonNestingDepth,
        int maxJsonStringLength,
        int maxBatchSize
) {
    public static final int DEFAULT_MAX_REQUEST_BODY_BYTES = 1024 * 1024;
    public static final int DEFAULT_MAX_QUERY_LENGTH = 4096;
    public static final int DEFAULT_MAX_JSON_NESTING_DEPTH = 32;
    public static final int DEFAULT_MAX_JSON_STRING_LENGTH = 64 * 1024;
    public static final int DEFAULT_MAX_BATCH_SIZE = 1000;

    public AdminRequestLimits {
        requirePositive(maxRequestBodyBytes, "maxRequestBodyBytes");
        if (maxRequestBodyBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be smaller than Integer.MAX_VALUE");
        }
        requirePositive(maxQueryLength, "maxQueryLength");
        requirePositive(maxJsonNestingDepth, "maxJsonNestingDepth");
        requirePositive(maxJsonStringLength, "maxJsonStringLength");
        requirePositive(maxBatchSize, "maxBatchSize");
    }

    public static AdminRequestLimits defaults() {
        return new AdminRequestLimits(
                DEFAULT_MAX_REQUEST_BODY_BYTES,
                DEFAULT_MAX_QUERY_LENGTH,
                DEFAULT_MAX_JSON_NESTING_DEPTH,
                DEFAULT_MAX_JSON_STRING_LENGTH,
                DEFAULT_MAX_BATCH_SIZE
        );
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
