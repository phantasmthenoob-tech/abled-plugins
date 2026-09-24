package net.abled.medieval.core.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for persistence tests.
 *
 * <p>Tests run against real SQLite files in a JUnit {@code @TempDir}, not mocks: that exercises the
 * shipped migrations, the JDBC layer and the SQL itself. No Minecraft server is involved.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    /** Opens a migrated database backed by {@code file}. */
    public static StorageService open(Path file) {
        return open(file, Migrations.all());
    }

    /** Opens a database with an explicit set of migrations. */
    public static StorageService open(Path file, List<Migration> migrations) {
        JdbcStorageService storage = new JdbcStorageService(connectionFactory(file), migrations, StorageLog.noop());
        storage.open();
        return storage;
    }

    public static ConnectionFactory connectionFactory(Path file) {
        return new DriverManagerConnectionFactory(
                "jdbc:sqlite:" + file.toAbsolutePath(),
                "org.sqlite.JDBC",
                List.of("PRAGMA foreign_keys = ON", "PRAGMA busy_timeout = 5000"));
    }

    /** A log that discards everything, for tests that assert on data instead of on output. */
    public static StorageLog logSilently() {
        return StorageLog.noop();
    }

    /** Captures log output so tests can assert that bad data is reported rather than ignored. */
    public static final class CollectingLog implements StorageLog {

        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            errors.add(message + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")"));
        }

        public List<String> infos() {
            return List.copyOf(infos);
        }

        public List<String> warnings() {
            return List.copyOf(warnings);
        }

        public List<String> errors() {
            return List.copyOf(errors);
        }
    }
}
