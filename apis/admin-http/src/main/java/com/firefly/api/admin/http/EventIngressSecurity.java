package com.firefly.api.admin.http;

import com.firefly.plugin.FireflyPluginContext;
import com.firefly.trigger.EventSignatureVerifier;

import java.time.Duration;

/** Effective security policy for the externally callable event ingress. */
record EventIngressSecurity(
        String secret,
        Duration maxClockSkew,
        Duration nonceTtl,
        int maxRequestBodyBytes
) {
    static EventIngressSecurity from(FireflyPluginContext context, AdminRequestLimits requestLimits) {
        var configuration = context.configuration();
        return new EventIngressSecurity(
                configuration.property("firefly.trigger.hmac-secret", ""),
                Duration.parse(configuration.property("firefly.trigger.signature-max-skew", "PT5M")),
                Duration.parse(configuration.property("firefly.trigger.nonce-ttl", "PT10M")),
                Integer.parseInt(configuration.property(
                        "firefly.trigger.max-request-body-bytes",
                        Integer.toString(Math.min(64 * 1024, requestLimits.maxRequestBodyBytes()))
                ))
        );
    }

    EventIngressSecurity {
        secret = secret == null ? "" : secret;
        if (maxClockSkew == null || maxClockSkew.isZero() || maxClockSkew.isNegative()) {
            throw new IllegalArgumentException("trigger signature max clock skew must be positive");
        }
        if (nonceTtl == null || nonceTtl.compareTo(maxClockSkew) < 0) {
            throw new IllegalArgumentException("trigger nonce TTL must be at least the signature clock skew");
        }
        if (maxRequestBodyBytes <= 0) {
            throw new IllegalArgumentException("trigger max request body bytes must be positive");
        }
    }

    EventSignatureVerifier verifier(FireflyPluginContext context) {
        if (secret.isBlank()) return null;
        return new EventSignatureVerifier(secret, context.clock(), maxClockSkew);
    }
}
