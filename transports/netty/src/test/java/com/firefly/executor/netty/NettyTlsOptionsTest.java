package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class NettyTlsOptionsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void rejectsIncompleteTlsIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new NettyTlsOptions(
                true, java.nio.file.Path.of("missing-cert.pem"), null, "", null, true, false
        ));
    }

    @Test
    void disabledTlsDoesNotCreateAContext() {
        assertNull(NettyTlsOptions.disabled().serverContext());
        assertNull(NettyTlsOptions.disabled().clientContext());
    }

    @Test
    void disabledReloadingContextDoesNotStartTls() {
        try (ReloadingNettyTlsContext context = new ReloadingNettyTlsContext(
                NettyTlsOptions.disabled(), java.time.Duration.ofSeconds(1)
        )) {
            assertNull(context.current());
            context.reloadIfChanged();
        }
    }

    @Test
    void gatewayOptionsRejectNonPositiveReloadInterval() {
        assertThrows(IllegalArgumentException.class, () -> new NettyExecutorGatewayOptions(
                10, 1024, NettyTlsOptions.disabled(), java.time.Duration.ZERO
        ));
    }

    @Tag("production-readiness")
    @Test
    void reloadsRotatedMutualTlsIdentityAndTrustForNewConnections() throws Exception {
        io.netty.handler.ssl.util.SelfSignedCertificate firstIdentity =
                new io.netty.handler.ssl.util.SelfSignedCertificate("gateway-a");
        io.netty.handler.ssl.util.SelfSignedCertificate secondIdentity =
                new io.netty.handler.ssl.util.SelfSignedCertificate("gateway-a");
        Path certificate = tempDirectory.resolve("gateway.crt");
        Path privateKey = tempDirectory.resolve("gateway.key");
        Path trustCertificates = tempDirectory.resolve("clients-ca.crt");
        try {
            Files.copy(firstIdentity.certificate().toPath(), certificate);
            Files.copy(firstIdentity.privateKey().toPath(), privateKey);
            Files.copy(firstIdentity.certificate().toPath(), trustCertificates);
            NettyTlsOptions options = new NettyTlsOptions(
                    true, certificate, privateKey, "", trustCertificates, true, false
            );
            try (ReloadingNettyTlsContext context = new ReloadingNettyTlsContext(
                    options, java.time.Duration.ofHours(1)
            )) {
                var before = context.current();
                Files.copy(secondIdentity.certificate().toPath(), certificate,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.copy(secondIdentity.privateKey().toPath(), privateKey,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.copy(secondIdentity.certificate().toPath(), trustCertificates,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.setLastModifiedTime(certificate,
                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));
                Files.setLastModifiedTime(privateKey,
                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));
                Files.setLastModifiedTime(trustCertificates,
                        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));

                context.reloadIfChanged();

                assertNotSame(before, context.current());
            }
        } finally {
            firstIdentity.delete();
            secondIdentity.delete();
        }
    }
}
