package com.firefly.execution;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/** Encodes replay target ids without relying on target-id delimiter restrictions. */
public final class ReplayTargetIds {
    private ReplayTargetIds() { }

    public static String encode(Collection<String> targetIds) {
        return targetIds.stream()
                .map(value -> Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    public static List<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        return java.util.Arrays.stream(encoded.split(",", -1))
                .map(value -> new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8))
                .toList();
    }
}
