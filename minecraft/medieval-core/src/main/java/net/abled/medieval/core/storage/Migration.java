package net.abled.medieval.core.storage;

import java.util.List;
import java.util.Objects;

/**
 * One schema migration.
 *
 * <p>Versions are zero-padded strings so their natural ordering matches their execution order.
 * Each migration is applied inside a single transaction together with its version row, so a
 * failure can never leave a half-applied schema behind.
 */
public record Migration(String version, List<String> statements) {

    public Migration {
        Objects.requireNonNull(version, "version");
        if (version.isBlank()) {
            throw new IllegalArgumentException("migration version must not be blank");
        }
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("migration " + version + " contains no statements");
        }
    }

    /** Builds a migration from a SQL script stored as a classpath resource. */
    public static Migration fromResource(String version, String resourcePath) {
        return new Migration(version, SqlScript.load(resourcePath));
    }
}
