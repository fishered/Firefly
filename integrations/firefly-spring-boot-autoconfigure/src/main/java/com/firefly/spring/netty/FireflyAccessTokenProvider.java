package com.firefly.spring.netty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.executor.netty.AuthTokenProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Thread-safe client-credentials token cache shared by Gateway and Admin calls. */
public final class FireflyAccessTokenProvider implements AuthTokenProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FireflyNettyExecutorProperties.Auth properties;
    private final String fixedToken;
    private final HttpClient client;
    private final Clock clock;
    private volatile Token token;

    public FireflyAccessTokenProvider(FireflyNettyExecutorProperties properties) {
        this(properties.getAuth(), properties.getAuthToken(), Clock.systemUTC());
    }

    FireflyAccessTokenProvider(
            FireflyNettyExecutorProperties.Auth properties, String fixedToken, Clock clock
    ) {
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.fixedToken = fixedToken == null ? "" : fixedToken;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String accessToken() {
        if (properties.getClientId() == null || properties.getClientId().isBlank()) return fixedToken;
        Token current = token;
        Instant refreshAt = current == null ? Instant.EPOCH : current.expiresAt().minus(refreshSkew());
        if (current != null && clock.instant().isBefore(refreshAt)) return current.value();
        synchronized (this) {
            current = token;
            refreshAt = current == null ? Instant.EPOCH : current.expiresAt().minus(refreshSkew());
            if (current != null && clock.instant().isBefore(refreshAt)) return current.value();
            token = requestToken();
            return token.value();
        }
    }

    public boolean usesClientCredentials() {
        return properties.getClientId() != null && !properties.getClientId().isBlank();
    }

    private Token requestToken() {
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            throw new IllegalStateException("firefly.executor.auth.client-secret is required");
        }
        try {
            String requestBody = JSON.writeValueAsString(Map.of(
                    "clientId", properties.getClientId(), "clientSecret", properties.getClientSecret()
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getTokenUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Firefly token endpoint returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            Map<String, Object> body = JSON.readValue(response.body(), new TypeReference<>() { });
            Object accessToken = body.get("accessToken");
            Object expiresIn = body.get("expiresIn");
            if (!(accessToken instanceof String value) || value.isBlank() || !(expiresIn instanceof Number ttl)) {
                throw new IllegalStateException("Firefly token endpoint returned an invalid response");
            }
            return new Token(value, clock.instant().plusSeconds(ttl.longValue()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while obtaining Firefly access token", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot obtain Firefly access token from " + properties.getTokenUrl(), e);
        }
    }

    private Duration refreshSkew() {
        Duration skew = properties.getRefreshSkew();
        return skew == null || skew.isNegative() ? Duration.ZERO : skew;
    }

    private record Token(String value, Instant expiresAt) { }
}
