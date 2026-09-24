package net.abled.medieval.core.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationsTest {

    @Test
    void shipsAtLeastTheInitialSchema() {
        List<Migration> migrations = Migrations.all();

        assertFalse(migrations.isEmpty());
        assertEquals("001", migrations.get(0).version());
    }

    @Test
    void versionsAreUniqueAndSorted() {
        List<String> versions = Migrations.all().stream().map(Migration::version).toList();
        List<String> sorted = new ArrayList<>(versions);
        sorted.sort(String::compareTo);

        assertEquals(sorted, versions, "migrations must be declared in ascending version order");
        assertEquals(versions.size(), versions.stream().distinct().count(), "versions must be unique");
    }

    @Test
    void everyMigrationContainsStatements() {
        for (Migration migration : Migrations.all()) {
            assertFalse(migration.statements().isEmpty(), "migration " + migration.version() + " is empty");
        }
    }

    @Test
    void rejectsBlankVersionsAndEmptyStatements() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Migration(" ", List.of("SELECT 1")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Migration("002", List.of()));
    }

    @Test
    void initialSchemaOnlyCoversImplementedSystems() {
        String schema = String.join("\n", Migrations.all().get(0).statements()).toLowerCase();

        assertTrue(schema.contains("create table players"));
        assertTrue(schema.contains("create table kingdoms"));
        assertTrue(schema.contains("create table kingdom_members"));
        assertTrue(schema.contains("create table claims"));
        assertTrue(schema.contains("create table deathbans"));
        assertTrue(schema.contains("create table world_state"));

        // Systems that do not exist yet must not have tables advertising them.
        assertFalse(schema.contains("create table wars"));
        assertFalse(schema.contains("create table sieges"));
        assertFalse(schema.contains("create table structures"));
    }
}
