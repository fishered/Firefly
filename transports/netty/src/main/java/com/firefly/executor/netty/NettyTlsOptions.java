package com.firefly.executor.netty;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.SSLException;
import java.nio.file.Files;
import java.nio.file.Path;

/** PEM-based TLS/mTLS settings shared by the Gateway and executor client. */
public record NettyTlsOptions(
        boolean enabled,
        Path certificateChain,
        Path privateKey,
        String privateKeyPassword,
        Path trustCertificates,
        boolean requireClientAuth,
        boolean verifyHostname
) {
    public NettyTlsOptions {
        privateKeyPassword = privateKeyPassword == null ? "" : privateKeyPassword;
        if (enabled) {
            if ((certificateChain == null) != (privateKey == null)) {
                throw new IllegalArgumentException("certificateChain and privateKey must be configured together");
            }
            if (certificateChain != null) requireReadable(certificateChain, "certificateChain");
            if (privateKey != null) requireReadable(privateKey, "privateKey");
            if (trustCertificates != null) requireReadable(trustCertificates, "trustCertificates");
        }
    }

    public static NettyTlsOptions disabled() {
        return new NettyTlsOptions(false, null, null, "", null, false, true);
    }

    SslContext serverContext() {
        if (!enabled) return null;
        requireReadable(certificateChain, "certificateChain");
        requireReadable(privateKey, "privateKey");
        try {
            SslContextBuilder builder = SslContextBuilder.forServer(
                    certificateChain.toFile(), privateKey.toFile(), passwordOrNull()
            );
            if (trustCertificates != null) builder.trustManager(trustCertificates.toFile());
            builder.clientAuth(requireClientAuth ? ClientAuth.REQUIRE : ClientAuth.NONE);
            return builder.build();
        } catch (SSLException e) {
            throw new IllegalArgumentException("failed to build Netty server TLS context", e);
        }
    }

    SslContext clientContext() {
        if (!enabled) return null;
        try {
            SslContextBuilder builder = SslContextBuilder.forClient();
            if (trustCertificates != null) builder.trustManager(trustCertificates.toFile());
            if (certificateChain != null && privateKey != null) {
                builder.keyManager(certificateChain.toFile(), privateKey.toFile(), passwordOrNull());
            }
            if (verifyHostname) builder.endpointIdentificationAlgorithm("HTTPS");
            return builder.build();
        } catch (SSLException e) {
            throw new IllegalArgumentException("failed to build Netty client TLS context", e);
        }
    }

    private String passwordOrNull() {
        return privateKeyPassword.isBlank() ? null : privateKeyPassword;
    }

    private static void requireReadable(Path path, String name) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(name + " must reference a readable PEM file");
        }
    }
}
