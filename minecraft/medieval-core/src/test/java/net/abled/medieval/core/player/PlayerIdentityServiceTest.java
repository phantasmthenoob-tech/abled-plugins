package net.abled.medieval.core.player;

import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.StorageLog;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerIdentityServiceTest {

    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant LOGIN = Instant.parse("2026-09-20T08:00:00Z");

    @TempDir
    Path tempDir;

    private StorageService storage;
    private PlayerIdentityService service;

    @BeforeEach
    void setUp() {
        storage = TestDatabases.open(tempDir.resolve("identity.db"));
        service = new PlayerIdentityService(new PlayerRepository(storage.database(), StorageLog.noop()));
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void resolvesUuidsFromNamesRegardlessOfCase() {
        service.recordLogin(PLAYER, "Disgraced_", LOGIN);

        assertEquals(PLAYER, service.findUuidByName("disgraced_").orElseThrow());
        assertEquals(PLAYER, service.findUuidByName("Disgraced_").orElseThrow());
    }

    @Test
    void reportsTheStoredName() {
        service.recordLogin(PLAYER, "Disgraced_", LOGIN);

        assertEquals("Disgraced_", service.nameOf(PLAYER).orElseThrow());
    }

    @Test
    void returnsEmptyForUnknownPlayers() {
        assertTrue(service.findUuidByName("Nobody").isEmpty());
        assertTrue(service.nameOf(PLAYER).isEmpty());
        assertTrue(service.find(PLAYER).isEmpty());
    }

    @Test
    void countsKnownProfiles() {
        assertEquals(0L, service.knownProfiles());

        service.recordLogin(PLAYER, "Disgraced_", LOGIN);

        assertEquals(1L, service.knownProfiles());
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> service.recordLogin(PLAYER, "", LOGIN));
    }

    @Test
    void exposesItsServiceName() {
        assertEquals("player-identity", service.name());
    }
}
