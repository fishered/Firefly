package com.firefly.integration.remote;

import com.firefly.executor.netty.NettyTlsOptions;

import java.nio.file.Path;

/** TLS settings exposed without leaking the Netty implementation to callers. */
public record RemoteAdapterTlsOptions(
        boolean enabled,
        Path certificateChain,
        Path privateKey,
        String privateKeyPassword,
        Path trustCertificates,
        boolean verifyHostname
) {
    public RemoteAdapterTlsOptions {
        privateKeyPassword = privateKeyPassword == null ? "" : privateKeyPassword;
    }

    public static RemoteAdapterTlsOptions disabled() {
        return new RemoteAdapterTlsOptions(false, null, null, "", null, true);
    }

    NettyTlsOptions toNetty() {
        return new NettyTlsOptions(
                enabled, certificateChain, privateKey, privateKeyPassword,
                trustCertificates, false, verifyHostname
        );
    }
}
