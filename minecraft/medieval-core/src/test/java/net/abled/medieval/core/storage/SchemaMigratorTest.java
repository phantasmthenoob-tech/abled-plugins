package net.abled.medieval.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorTest {

    // Intentionally not "IF NOT EXISTS": re-running either migration would fail loudly, which is
    // exactly how the idempotency tests below prove migrations are applied only once.
    private static final Migration FIRST =
            new Migration("001", List.of("CREATE TABLE first_table (id INTEGER PRIMARY KEY)"));
    private static final Migration SECOND =
            new Migration("002", List.of("CREATE TABLE second_table (id INTEGER PRIMARY KEY)"));

    @TempDir
    Path tempDir;

    @Test
    void appliesEveryMigrationToAnEmptyDatabase() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("fresh.db"), List.of(FIRST, SECOND))) {
            assertEquals(List.of("001", "002"), storage.appliedMigrations());
            assertTrue(tableExists(storage.database(), "first_table"));
            assertTrue(tableExists(storage.database(), "second_table"));
        }
    }

    @Test
    void recordsEachAppliedVersion() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("recorded.db"), List.of(FIRST, SECOND))) {
            Long recorded = storage.database()
                    .queryOne("SELECT COUNT(*) AS total FROM schema_version", SqlBinder.none(),
                            row -> row.getLong("total"))
                    .orElseThrow();

            assertEquals(2L, recorded);
        }
    }

    @Test
    void isIdempotentAcrossRestarts() {
        Path file = tempDir.resolve("restart.db");

        try (StorageService first = TestDatabases.open(file, List.of(FIRST))) {
            assertEquals(List.of("001"), first.appliedMigrations());
        }

        // Opening again must not re-run migration 001, which would throw "table already exists".
        try (StorageService reopened = TestDatabases.open(file, List.of(FIRST))) {
            assertEquals(List.of("001"), reopened.appliedMigrations());
            assertTrue(tableExists(reopened.database(), "first_table"));
        }
    }

    @Test
    void appliesOnlyPendingMigrationsOnUpgrade() {
        Path file = tempDir.resolve("upgrade.db");

        try (StorageService before = TestDatabases.open(file, List.of(FIRST))) {
            assertEquals(List.of("001"), before.appliedMigrations());
        }

        try (StorageService upgraded = TestDatabases.open(file, List.of(FIRST, SECOND))) {
            assertEquals(List.of("001", "002"), upgraded.appliedMigrations());
            assertTrue(tableExists(upgraded.database(), "second_table"));
        }
    }

    @Test
    void refusesToStartAgainstANewerSchema() {
        Path file = tempDir.resolve("newer.db");

        try (StorageService newer = TestDatabases.open(file, List.of(FIRST, SECOND))) {
            assertEquals(2, newer.appliedMigrations().size());
        }

        StorageException failure = assertThrows(StorageException.class,
                () -> TestDatabases.open(file, List.of(FIRST)));
        assertTrue(failure.getMessage().contains("newer"), failure.getMessage());
    }

    @Test
    void rejectsDuplicateVersionDefinitions() {
        Migration duplicate = new Migration("001", List.of("CREATE TABLE other_table (id INTEGER PRIMARY KEY)"));

        StorageException failure = assertThrows(StorageException.class,
                () -> TestDatabases.open(tempDir.resolve("duplicate.db"), List.of(FIRST, duplicate)));
        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    void rejectsUsingStorageBeforeItIsOpened() {
        JdbcStorageService storage = new JdbcStorageService(
                TestDatabases.connectionFactory(tempDir.resolve("unopened.db")), Migrations.all(), StorageLog.noop());

        assertFalse(storage.isOpen());
        assertThrows(StorageException.class, storage::database);
    }

    @Test
    void rollsBackAFailingMigration() {
        Migration broken = new Migration("002", List.of(
                "CREATE TABLE survives_only_if_committed (id INTEGER PRIMARY KEY)",
                "THIS IS NOT VALID SQL"));

        assertThrows(StorageException.class,
                () -> TestDatabases.open(tempDir.resolve("broken.db"), List.of(FIRST, broken)));

        // The first statement of the failed migration must not have been committed, and the
        // database must still report only the last successful version.
        try (StorageService reopened = TestDatabases.open(tempDir.resolve("broken.db"), List.of(FIRST))) {
            assertEquals(List.of("001"), reopened.appliedMigrations());
            assertFalse(tableExists(reopened.database(), "survives_only_if_committed"));
        }
    }

    private static boolean tableExists(Database database, String table) {
        return database.queryOne("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                statement -> statement.setString(1, table),
                row -> row.getString("name")).isPresent();
    }
}
