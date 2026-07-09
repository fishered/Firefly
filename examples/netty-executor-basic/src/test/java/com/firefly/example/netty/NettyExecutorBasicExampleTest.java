package com.firefly.example.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class NettyExecutorBasicExampleTest {
    @Test
    void exampleClassIsLoadable() {
        assertDoesNotThrow(() -> Class.forName("com.firefly.example.netty.NettyExecutorBasicExample"));
    }
}
