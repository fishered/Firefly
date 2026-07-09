package com.firefly.example.embedded;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class EmbeddedBasicExampleTest {
    @Test
    void exampleClassIsLoadable() {
        assertDoesNotThrow(() -> Class.forName("com.firefly.example.embedded.EmbeddedBasicExample"));
    }
}
