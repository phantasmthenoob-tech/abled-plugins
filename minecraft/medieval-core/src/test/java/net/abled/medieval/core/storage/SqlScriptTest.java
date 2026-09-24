package net.abled.medieval.core.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptTest {

    @Test
    void splitsStatementsOnSemicolons() {
        List<String> statements = SqlScript.split("CREATE TABLE a (id INT); CREATE TABLE b (id INT);");

        assertEquals(2, statements.size());
        assertEquals("CREATE TABLE a (id INT)", statements.get(0));
        assertEquals("CREATE TABLE b (id INT)", statements.get(1));
    }

    @Test
    void stripsLineComments() {
        String script = """
                -- leading comment
                CREATE TABLE a (id INT); -- trailing comment
                -- comment between statements
                CREATE TABLE b (id INT);
                """;

        List<String> statements = SqlScript.split(script);

        assertEquals(2, statements.size());
        assertEquals("CREATE TABLE a (id INT)", statements.get(0));
        assertEquals("CREATE TABLE b (id INT)", statements.get(1));
    }

    @Test
    void ignoresBlankStatements() {
        assertEquals(List.of(), SqlScript.split(";;   \n ; -- only a comment\n"));
    }

    @Test
    void loadsShippedMigrationsFromTheClasspath() {
        List<String> statements = SqlScript.load("db/migration/001_init.sql");

        assertTrue(statements.size() >= 6, "expected the initial schema to define several tables");
        assertTrue(statements.stream().anyMatch(s -> s.contains("CREATE TABLE deathbans")), statements.toString());
        assertTrue(statements.stream().anyMatch(s -> s.contains("CREATE TABLE players")), statements.toString());
    }

    @Test
    void reportsMissingResources() {
        assertThrows(StorageException.class, () -> SqlScript.load("db/migration/does-not-exist.sql"));
    }
}
