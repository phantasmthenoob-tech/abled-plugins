package net.abled.medieval.core.world;

import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.StorageLog;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStateRepositoryTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private WorldStateRepository state;

    @BeforeEach
    void setUp() {
        storage = TestDatabases.open(tempDir.resolve("world-state.db"));
        state = new WorldStateRepository(storage.database(), StorageLog.noop());
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void storesAndOverwritesValues() {
        state.put(WorldStateRepository.KEY_NETHER_ENABLED, "false");
        state.put(WorldStateRepository.KEY_NETHER_ENABLED, "true");

        assertEquals("true", state.get(WorldStateRepository.KEY_NETHER_ENABLED).orElseThrow());
    }

    @Test
    void readsBooleansWithAFallback() {
        assertFalse(state.getBoolean(WorldStateRepository.KEY_END_ENABLED, false));
        assertTrue(state.getBoolean("missing.key", true));

        state.putBoolean(WorldStateRepository.KEY_END_ENABLED, true);
        assertTrue(state.getBoolean(WorldStateRepository.KEY_END_ENABLED, false));

        state.put("weird.key", "perhaps");
        assertFalse(state.getBoolean("weird.key", false), "unparseable values fall back");
    }

    @Test
    void removesValues() {
        state.put("event.siege", "active");

        assertTrue(state.remove("event.siege"));
        assertFalse(state.remove("event.siege"));
        assertTrue(state.get("event.siege").isEmpty());
    }

    @Test
    void listsEveryEntry() {
        state.put(WorldStateRepository.KEY_NETHER_ENABLED, "true");
        state.put(WorldStateRepository.KEY_END_ENABLED, "false");

        assertEquals(2, state.all().size());
        assertEquals("true", state.all().get(WorldStateRepository.KEY_NETHER_ENABLED));
    }

    @Test
    void rejectsBlankKeys() {
        assertThrows(IllegalArgumentException.class, () -> state.put(" ", "value"));
        assertThrows(IllegalArgumentException.class, () -> state.get(""));
        assertThrows(IllegalArgumentException.class, () -> state.remove(null));
    }

    @Test
    void survivesReopeningTheDatabase() {
        Path file = tempDir.resolve("persisted-state.db");
        try (StorageService first = TestDatabases.open(file)) {
            new WorldStateRepository(first.database(), StorageLog.noop())
                    .putBoolean(WorldStateRepository.KEY_NETHER_ENABLED, true);
        }

        try (StorageService reopened = TestDatabases.open(file)) {
            WorldStateRepository reopenedState = new WorldStateRepository(reopened.database(), StorageLog.noop());
            assertTrue(reopenedState.getBoolean(WorldStateRepository.KEY_NETHER_ENABLED, false));
        }
    }
}
