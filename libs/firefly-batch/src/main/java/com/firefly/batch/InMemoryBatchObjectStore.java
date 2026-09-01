package com.firefly.batch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test/local object store; production deployments should provide S3-compatible storage. */
public final class InMemoryBatchObjectStore implements BatchObjectStore {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    public StoredObject put(String key, InputStream content, long size, String checksum) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(size, 1_048_576));
            content.transferTo(out);
            byte[] bytes = out.toByteArray();
            if (bytes.length != size) throw new IllegalArgumentException("object size mismatch");
            objects.put(key, bytes);
            return new StoredObject("memory://" + key, size, checksum);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to store batch object", e);
        }
    }
    public InputStream get(String location) {
        if (location == null || !location.startsWith("memory://")) throw new IllegalArgumentException("unsupported object location");
        byte[] value = objects.get(location.substring("memory://".length()));
        if (value == null) throw new IllegalArgumentException("object not found");
        return new ByteArrayInputStream(value);
    }
}
