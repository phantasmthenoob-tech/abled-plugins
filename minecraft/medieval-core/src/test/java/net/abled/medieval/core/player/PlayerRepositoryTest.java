package net.abled.medieval.core.player;

import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRepositoryTest {

    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant FIRST_LOGIN = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant SECOND_LOGIN = Instant.parse("2026-09-02T18:30:00Z");

    @TempDir
    Path tempDir;

    private StorageService storage;
    private TestDatabases.CollectingLog log;
    private PlayerRepository repository;

    @BeforeEach
    void setUp() {
        log = new TestDatabases.CollectingLog();
        storage = TestDatabases.open(tempDir.resolve("players.db"));
        repository = new PlayerRepository(storage.database(), log);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void recordsAndReadsBackAProfile() {
        repository.recordLogin(STEVE, "Steve", FIRST_LOGIN);

        PlayerRecord profile = repository.findByUuid(STEVE).orElseThrow();

        assertEquals(STEVE, profile.playerId());
        assertEquals("Steve", profile.name());
        assertEquals(FIRST_LOGIN, profile.firstSeen());
        assertEquals(FIRST_LOGIN, profile.lastSeen());
    }

    @Test
    void keepsFirstSeenAndRefreshesLaterLogins() {
        repository.recordLogin(STEVE, "Steve", FIRST_LOGIN);
        repository.recordLogin(STEVE, "Steve_The_Builder", SECOND_LOGIN);

        PlayerRecord profile = repository.findByUuid(STEVE).orElseThrow();

        assertEquals("Steve_The_Builder", profile.name());
        assertEquals(FIRST_LOGIN, profile.firstSeen(), "first seen must not move");
        assertEquals(SECOND_LOGIN, profile.lastSeen());
        assertEquals(1L, repository.count(), "the same UUID must never create a second row");
    }

    @Test
    void findsProfilesByNameCaseInsensitively() {
        repository.recordLogin(STEVE, "Steve", FIRST_LOGIN);

        assertEquals(STEVE, repository.findByName("steve").orElseThrow().playerId());
        assertEquals(STEVE, repository.findByName("STEVE").orElseThrow().playerId());
    }

    @Test
    void returnsEmptyForUnknownPlayers() {
        assertTrue(repository.findByUuid(STEVE).isEmpty());
        assertTrue(repository.findByName("Nobody").isEmpty());
        assertEquals(0L, repository.count());
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> repository.recordLogin(STEVE, "  ", FIRST_LOGIN));
        assertThrows(NullPointerException.class, () -> repository.recordLogin(null, "Steve", FIRST_LOGIN));
    }

    @Test
    void reportsMalformedRowsInsteadOfReturningGarbage() {
        storage.database().update(
                "INSERT INTO players (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)",
                statement -> {
                    statement.setString(1, "not-a-uuid");
                    statement.setString(2, "Broken");
                    statement.setLong(3, FIRST_LOGIN.toEpochMilli());
                    statement.setLong(4, FIRST_LOGIN.toEpochMilli());
                });

        assertTrue(repository.findByName("Broken").isEmpty());
        assertFalse(log.errors().isEmpty(), "a malformed row must be reported");
    }

    @Test
    void countsStoredProfiles() {
        repository.recordLogin(STEVE, "Steve", FIRST_LOGIN);
        repository.recordLogin(UUID.randomUUID(), "Alex", FIRST_LOGIN);

        assertEquals(2L, repository.count());
    }

    @Test
    void survivesClosingAndReopeningTheDatabase() {
        Path file = tempDir.resolve("reopen.db");
        try (StorageService first = TestDatabases.open(file)) {
            new PlayerRepository(first.database(), log).recordLogin(STEVE, "Steve", FIRST_LOGIN);
        }

        try (StorageService reopened = TestDatabases.open(file)) {
            PlayerRepository reopenedRepository = new PlayerRepository(reopened.database(), log);
            assertEquals("Steve", reopenedRepository.findByUuid(STEVE).orElseThrow().name());
            assertEquals(1L, reopenedRepository.count());
        }
    }
}
