package io.github.nishi.firefly.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FireflyCliTest {
    @Test
    void describesPlaceholderStatus() {
        assertTrue(FireflyCli.description().contains("CLI placeholder"));
    }
}
