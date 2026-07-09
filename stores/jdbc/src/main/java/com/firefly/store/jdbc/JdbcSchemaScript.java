package com.firefly.store.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class JdbcSchemaScript {
    private JdbcSchemaScript() {
    }

    static List<String> load(JdbcDialect dialect) {
        String resource = "/com/firefly/store/jdbc/schema/" + dialect.id() + ".sql";
        try (InputStream inputStream = JdbcSchemaScript.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new JdbcException("missing jdbc schema resource: " + resource);
            }
            return parse(inputStream);
        } catch (IOException e) {
            throw new JdbcException("failed to load jdbc schema resource: " + resource, e);
        }
    }

    /**
     * Schema resources are intentionally simple: semicolon-separated statements and line comments.
     */
    private static List<String> parse(InputStream inputStream) throws IOException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                current.append(line).append(System.lineSeparator());
                if (trimmed.endsWith(";")) {
                    current.setLength(current.lastIndexOf(";"));
                    statements.add(current.toString().trim());
                    current.setLength(0);
                }
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return List.copyOf(statements);
    }
}
