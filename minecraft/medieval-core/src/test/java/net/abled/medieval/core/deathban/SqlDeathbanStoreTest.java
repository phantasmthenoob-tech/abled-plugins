package net.abled.medieval.core.deathban;

import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDeathbanStoreTest {

    private static final UUID PLAYER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OTHER = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant DEATH = Instant.parse("2026-09-24T12:00:00Z");

    @TempDir
    Path tempDir;

    private StorageService storage;
    private TestDatabases.CollectingLog log;
    private SqlDeathbanStore store;

    @BeforeEach
    void setUp() {
        log = new TestDatabases.CollectingLog();
        storage = TestDatabases.open(tempDir.resolve("deathbans.db"));
        store = new SqlDeathbanStore(storage.database(), log);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void savesAndFindsBans() {
        DeathbanEntry entry = new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(1)), "zombie", "Herobrine");

        store.save(entry);

        DeathbanEntry stored = store.find(PLAYER).orElseThrow();
        assertEquals(entry, stored);
        assertEquals("zombie", stored.cause());
        assertEquals("Herobrine", stored.killer());
    }

    @Test
    void overwritesAnExistingBanForTheSamePlayer() {
        store.save(new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(1)), "zombie", "unknown"));
        store.save(new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(2)), "creeper", "Alex"));

        assertEquals(1, store.all().size(), "one row per UUID");
        assertEquals(Duration.ofHours(2), store.find(PLAYER).orElseThrow()
                .remaining(DEATH));
        assertEquals("creeper", store.find(PLAYER).orElseThrow().cause());
    }

    @Test
    void deletesBans() {
        store.save(new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(1)), "zombie", "unknown"));

        assertTrue(store.delete(PLAYER));
        assertFalse(store.delete(PLAYER));
        assertTrue(store.find(PLAYER).isEmpty());
    }

    @Test
    void listsBansOrderedByExpiry() {
        store.save(new DeathbanEntry(OTHER, DEATH, DEATH.plus(Duration.ofHours(5)), "fall", "unknown"));
        store.save(new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(1)), "zombie", "unknown"));

        List<UUID> order = store.all().stream().map(DeathbanEntry::playerId).toList();

        assertEquals(List.of(PLAYER, OTHER), order);
    }

    @Test
    void deletesOnlyExpiredBans() {
        store.save(new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(1)), "zombie", "unknown"));
        store.save(new DeathbanEntry(OTHER, DEATH, DEATH.plus(Duration.ofHours(5)), "fall", "unknown"));

        int removed = store.deleteExpired(DEATH.plus(Duration.ofHours(2)));

        assertEquals(1, removed);
        assertTrue(store.find(PLAYER).isEmpty());
        assertTrue(store.find(OTHER).isPresent());
    }

    @Test
    void treatsTheExpiryInstantAsInclusive() {
        store.save(new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(1)), "zombie", "unknown"));

        assertEquals(1, store.deleteExpired(DEATH.plus(Duration.ofHours(1))));
    }

    @Test
    void skipsAndReportsUnreadableRows() {
        store.save(new DeathbanEntry(PLAYER, DEATH, DEATH.plus(Duration.ofHours(1)), "zombie", "unknown"));
        storage.database().update(
                "INSERT INTO deathbans (uuid, death_at, expires_at, cause, killer) VALUES (?, ?, ?, ?, ?)",
                statement -> {
                    statement.setString(1, "not-a-uuid");
                    statement.setLong(2, DEATH.toEpochMilli());
                    statement.setLong(3, DEATH.plus(Duration.ofHours(1)).toEpochMilli());
                    statement.setString(4, "broken");
                    statement.setString(5, "broken");
                });

        assertEquals(1, store.all().size(), "the readable row is still returned");
        assertFalse(log.errors().isEmpty(), "the unreadable row must be reported");
    }
}
