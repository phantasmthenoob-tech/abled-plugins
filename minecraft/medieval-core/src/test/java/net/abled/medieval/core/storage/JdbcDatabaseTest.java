package net.abled.medieval.core.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDatabaseTest {

    @TempDir
    Path tempDir;

    private JdbcDatabase database;

    @BeforeEach
    void createProbeTable() {
        database = new JdbcDatabase(TestDatabases.connectionFactory(tempDir.resolve("probe.db")), StorageLog.noop());
        database.update("CREATE TABLE probe (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT NOT NULL)",
                SqlBinder.none());
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    void executesStatementsAndReadsThemBack() {
        database.update("INSERT INTO probe (label) VALUES (?)", statement -> statement.setString(1, "longsword"));

        String label = database.queryOne("SELECT label FROM probe", SqlBinder.none(), row -> row.getString("label"))
                .orElseThrow();

        assertEquals("longsword", label);
        assertEquals(1L, countRows());
    }

    @Test
    void returnsGeneratedKeys() {
        long first = database.insert("INSERT INTO probe (label) VALUES (?)", statement -> statement.setString(1, "a"));
        long second = database.insert("INSERT INTO probe (label) VALUES (?)", statement -> statement.setString(1, "b"));

        assertEquals(1L, first);
        assertEquals(2L, second);
    }

    @Test
    void commitsSuccessfulTransactions() {
        Long id = database.transaction(active ->
                active.insert("INSERT INTO probe (label) VALUES (?)", statement -> statement.setString(1, "kept")));

        assertEquals(1L, id);
        assertEquals(1L, countRows());
    }

    @Test
    void rollsBackWhenTheWorkThrows() {
        assertThrows(IllegalStateException.class, () -> database.transaction(active -> {
            active.update("INSERT INTO probe (label) VALUES (?)", statement -> statement.setString(1, "discarded"));
            throw new IllegalStateException("deliberate failure");
        }));

        assertEquals(0L, countRows(), "a failed transaction must leave nothing behind");
    }

    @Test
    void rollsBackWhenAStatementFails() {
        StorageException failure = assertThrows(StorageException.class, () -> database.transaction(active -> {
            active.update("INSERT INTO probe (label) VALUES (?)", statement -> statement.setString(1, "first"));
            active.update("INSERT INTO probe (label, missing_column) VALUES (?, ?)", statement -> {
                statement.setString(1, "second");
                statement.setString(2, "third");
            });
            return null;
        }));

        assertEquals(0L, countRows(), "the first insert must be rolled back with the failing one");
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test
    void rejectsNestedTransactions() {
        StorageException failure = assertThrows(StorageException.class, () -> database.transaction(outer -> {
            database.transaction(inner -> null);
            return null;
        }));

        assertTrue(failure.getMessage().contains("nested"), failure.getMessage());
    }

    @Test
    void reportsSqlFailuresWithStatementContext() {
        StorageException failure = assertThrows(StorageException.class,
                () -> database.update("UPDATE probe SET does_not_exist = 1", SqlBinder.none()));

        assertTrue(failure.getMessage().contains("update failed"), failure.getMessage());
        assertTrue(failure.getMessage().contains("does_not_exist"), failure.getMessage());
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test
    void failsClearlyOnceClosed() {
        database.close();

        assertTrue(!database.isOpen());
        StorageException failure = assertThrows(StorageException.class,
                () -> database.update("INSERT INTO probe (label) VALUES (?)", statement -> statement.setString(1, "x")));
        assertTrue(failure.getMessage().contains("closed"), failure.getMessage());
    }

    @Test
    void reportsMissingGeneratedKeys() {
        // An UPDATE executes successfully but returns no row id, which is the case this guard
        // exists for: without it the caller would store the 0/-1 that getGeneratedKeys leaves
        // behind as if it were a real id. (A SELECT never reaches this branch - sqlite-jdbc
        // rejects it earlier with "Query returns results".)
        StorageException failure = assertThrows(StorageException.class,
                () -> database.insert("UPDATE probe SET label = 'x' WHERE id = 999", SqlBinder.none()));

        assertTrue(failure.getMessage().contains("generated key"), failure.getMessage());
    }

    @Test
    void surfacesAStatementThatCannotBeExecutedAsAWrite() {
        // Verified against sqlite-jdbc 3.53.4.0: selecting with RETURN_GENERATED_KEYS fails in the
        // driver. The message must still name the operation and the statement.
        StorageException failure = assertThrows(StorageException.class,
                () -> database.insert("SELECT 1", SqlBinder.none()));

        assertTrue(failure.getMessage().contains("insert failed"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SELECT 1"), failure.getMessage());
    }

    private long countRows() {
        Long total = database.queryOne("SELECT COUNT(*) AS total FROM probe", SqlBinder.none(),
                row -> row.getLong("total")).orElse(0L);
        return total == null ? 0L : total;
    }
}
