package com.firefly.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Minimal HS256 JWT issuer and verifier used by both HTTP and Netty boundaries. */
public final class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] HEADER = bytes("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

    private final byte[] secret;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(String secret, String issuer, Duration accessTokenTtl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("JWT issuer must not be blank");
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("JWT access token TTL must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public String issue(JwtClient client) {
        return issue(client.clientId(), client.roles(), client.executorNames(), "client", -1);
    }

    public String issueUser(String subject, Set<FireflyRole> roles, long identityVersion) {
        return issue(subject, roles, Set.of(), "user", identityVersion);
    }

    private String issue(
            String subject, Set<FireflyRole> roles, Set<String> executorNames,
            String identityType, long identityVersion
    ) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("JWT subject must not be blank");
        if (roles == null || roles.isEmpty()) throw new IllegalArgumentException("JWT roles must not be empty");
        if (executorNames == null) throw new IllegalArgumentException("JWT executorNames must not be null");
        Instant now = clock.instant();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", subject);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(accessTokenTtl).getEpochSecond());
        claims.put("jti", java.util.UUID.randomUUID().toString());
        claims.put("identityType", identityType);
        if (identityVersion >= 0) claims.put("identityVersion", identityVersion);
        claims.put("roles", roles.stream().map(Enum::name).sorted().toList());
        claims.put("executorNames", executorNames.stream().sorted().toList());
        try {
            String unsigned = encode(HEADER) + "." + encode(JSON.writeValueAsBytes(claims));
            return unsigned + "." + encode(sign(unsigned));
        } catch (Exception e) {
            throw new IllegalStateException("cannot issue JWT", e);
        }
    }

    public FireflyPrincipal verify(String token) {
        try {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("access token is missing");
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException("malformed JWT");
            Map<String, Object> header = JSON.readValue(DECODER.decode(parts[0]), new TypeReference<>() { });
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw new IllegalArgumentException("unsupported JWT header");
            }
            if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), DECODER.decode(parts[2]))) {
                throw new IllegalArgumentException("invalid JWT signature");
            }
            Map<String, Object> claims = JSON.readValue(DECODER.decode(parts[1]), new TypeReference<>() { });
            if (!issuer.equals(text(claims, "iss"))) throw new IllegalArgumentException("invalid JWT issuer");
            Instant now = clock.instant();
            Instant issuedAt = Instant.ofEpochSecond(number(claims, "iat"));
            Instant expiresAt = Instant.ofEpochSecond(number(claims, "exp"));
            if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("JWT has expired");
            if (issuedAt.isAfter(now.plusSeconds(30))) throw new IllegalArgumentException("JWT issued-at is in the future");
            Set<FireflyRole> roles = strings(claims, "roles").stream()
                    .map(FireflyRole::valueOf).collect(Collectors.toUnmodifiableSet());
            if (roles.isEmpty()) throw new IllegalArgumentException("JWT has no roles");
            Object identityType = claims.getOrDefault("identityType", "client");
            if (!(identityType instanceof String type)) throw new IllegalArgumentException("invalid JWT identityType");
            Object identityVersion = claims.getOrDefault("identityVersion", -1);
            if (!(identityVersion instanceof Number version)) {
                throw new IllegalArgumentException("invalid JWT identityVersion");
            }
            return new FireflyPrincipal(text(claims, "sub"), roles,
                    Set.copyOf(strings(claims, "executorNames")), issuedAt, expiresAt, type, version.longValue());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JWT", e);
        }
    }

    public long expiresInSeconds() {
        return accessTokenTtl.toSeconds();
    }

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(bytes(content));
    }

    private static String encode(byte[] value) {
        return ENCODER.encodeToString(value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("JWT " + name + " is missing");
        return text;
    }

    private static long number(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("JWT " + name + " is missing");
        return number.longValue();
    }

    private static List<String> strings(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException("JWT " + name + " must be an array");
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) throw new IllegalArgumentException("JWT " + name + " contains an invalid value");
            result.add(text);
        }
        return result;
    }
}
