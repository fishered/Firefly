package com.firefly.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FireflyPluginDiscoveryTest {
    @Test
    void discoversPluginFromExternalJar(@TempDir Path directory) throws IOException {
        Path jar = directory.resolve("external-test.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addClass(output, ExternalTestPlugin.class);
            output.putNextEntry(new JarEntry("META-INF/services/" + FireflyPlugin.class.getName()));
            output.write((ExternalTestPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (FireflyPluginDiscovery discovery = FireflyPluginDiscovery.discover(
                directory, FireflyPlugin.class.getClassLoader()
        )) {
            assertEquals(java.util.List.of("external-test"),
                    discovery.plugins().stream().map(FireflyPlugin::id).toList());
        }
    }

    private static void addClass(JarOutputStream output, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        output.putNextEntry(new JarEntry(resource));
        try (var input = type.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("class resource not found: " + resource);
            input.transferTo(output);
        }
        output.closeEntry();
    }
}
