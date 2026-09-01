package com.firefly.batch;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Verifies checkpoint bytes before they are accepted for recovery. */
public final class BatchCheckpointVerifier {
    public boolean verify(InputStream content, String expectedChecksum) {
        if (content == null || expectedChecksum == null || expectedChecksum.isBlank()) return false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            content.transferTo(new java.io.OutputStream() { public void write(int b) { digest.update((byte) b); } public void write(byte[] b, int o, int l) { digest.update(b, o, l); } });
            return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedChecksum);
        } catch (Exception e) { return false; }
    }
}
