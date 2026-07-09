package io.github.nishi.firefly.store.jdbc;

import io.github.nishi.firefly.cluster.NodeRole;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class JdbcEncoding {
    private JdbcEncoding() {
    }

    static String encodeRoles(Set<NodeRole> roles) {
        return roles.stream()
                .map(NodeRole::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    static Set<NodeRole> decodeRoles(String value) {
        return Arrays.stream(value.split(","))
                .filter(role -> !role.isBlank())
                .map(NodeRole::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    static String encodeMap(Map<String, String> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> escape(entry.getKey()) + "=" + escape(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    static Map<String, String> decodeMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(value.split("&"))
                .filter(part -> !part.isBlank())
                .map(part -> part.split("=", 2))
                .collect(Collectors.toUnmodifiableMap(
                        part -> unescape(part[0]),
                        part -> part.length == 2 ? unescape(part[1]) : ""
                ));
    }

    private static String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
