package net.abled.medieval.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Loads and splits SQL scripts used for schema migrations. */
public final class SqlScript {

    private SqlScript() {
    }

    /** Reads a script from the classpath and splits it into individual statements. */
    public static List<String> load(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");

        ClassLoader loader = SqlScript.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new StorageException("SQL resource is missing from the classpath: " + resourcePath);
            }
            List<String> statements = split(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            if (statements.isEmpty()) {
                throw new StorageException("SQL resource contains no statements: " + resourcePath);
            }
            return statements;
        } catch (IOException failure) {
            throw new StorageException("could not read SQL resource: " + resourcePath, failure);
        }
    }

    /**
     * Splits a script into statements: {@code --} line comments are stripped and the remainder is
     * split on {@code ;}.
     *
     * <p>Migrations must therefore not contain a semicolon inside a string literal or inside a
     * trigger body. The schema tests assert that the shipped scripts load as expected.
     */
    public static List<String> split(String script) {
        Objects.requireNonNull(script, "script");

        StringBuilder withoutComments = new StringBuilder(script.length());
        for (String rawLine : script.split("\n", -1)) {
            int commentStart = rawLine.indexOf("--");
            withoutComments.append(commentStart >= 0 ? rawLine.substring(0, commentStart) : rawLine).append('\n');
        }

        List<String> statements = new ArrayList<>();
        for (String candidate : withoutComments.toString().split(";")) {
            String statement = candidate.trim();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return List.copyOf(statements);
    }
}
