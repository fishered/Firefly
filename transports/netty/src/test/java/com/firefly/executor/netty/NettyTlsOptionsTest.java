package com.firefly.executor.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NettyTlsOptionsTest {
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
}
