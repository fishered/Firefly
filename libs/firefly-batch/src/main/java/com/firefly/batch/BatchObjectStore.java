package com.firefly.batch;

import java.io.InputStream;

/** Stores large batch payloads outside execution rows and transport frames. */
public interface BatchObjectStore {
    StoredObject put(String key, InputStream content, long size, String checksum);
    InputStream get(String location);
    record StoredObject(String location, long size, String checksum) {}
}
