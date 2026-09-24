package net.abled.medieval.core.deathban;

import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deathban behaviour across a database reopen, which is what a server restart looks like from the
 * plugin's point of view. Each "restart" opens the same SQLite file through a new service.
 */
class DeathbanPersistenceTest {

    private static final UUID BANNED = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID OTHER = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final Instant DEATH = Instant.parse("2026-09-24T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void banSurvivesReopeningTheDatabase() {
        Path file = tempDir.resolve("restart.db");

        try (StorageService first = TestDatabases.open(file)) {
            DeathbanService service = service(first, true, Duration.ofHours(1), DEATH);
            assertTrue(service.ban(BANNED, "zombie", "Herobrine", DEATH).isPresent());
        }

        try (StorageService reopened = TestDatabases.open(file)) {
            DeathbanService service = service(reopened, true, Duration.ofHours(1), DEATH.plus(Duration.ofMinutes(10)));

            assertTrue(service.isBanned(BANNED), "a ban must not be lost on restart");
            assertEquals(Duration.ofMinutes(50), service.remaining(BANNED).orElseThrow());
        }
    }

    @Test
    void expiredBanIsPurgedWhenThePlayerIsChecked() {
        Path file = tempDir.resolve("expired.db");

        try (StorageService first = TestDatabases.open(file)) {
            service(first, true, Duration.ofHours(1), DEATH).ban(BANNED, "zombie", "unknown", DEATH);
        }

        try (StorageService reopened = TestDatabases.open(file)) {
            DeathbanService service = service(reopened, true, Duration.ofHours(1), DEATH.plus(Duration.ofHours(2)));

            assertFalse(service.isBanned(BANNED));
            assertTrue(service.purgeIfExpired(BANNED), "the expired row must be removed");
            assertTrue(service.purgeIfExpired(BANNED) == false, "and removal must not repeat");
            assertTrue(new SqlDeathbanStore(reopened.database(), TestDatabases.logSilently())
                    .find(BANNED).isEmpty(), "the row is gone from storage");
        }
    }

    @Test
    void banDurationComesFromConfiguration() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("configured.db"))) {
            DeathbanService service = service(storage, true, Duration.ofMinutes(15), DEATH);

            DeathbanEntry entry = service.ban(BANNED, "fall", "unknown", DEATH).orElseThrow();

            assertEquals(DEATH.plus(Duration.ofMinutes(15)), entry.expiresAt());
        }
    }

    @Test
    void nothingIsRecordedWhenDeathbanIsDisabled() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("disabled.db"))) {
            DeathbanService service = service(storage, false, Duration.ofHours(1), DEATH);

            assertTrue(service.ban(BANNED, "fall", "unknown", DEATH).isEmpty());
            assertFalse(service.isBanned(BANNED));
            assertTrue(new SqlDeathbanStore(storage.database(), TestDatabases.logSilently()).all().isEmpty());
        }
    }

    @Test
    void administrativeBanIgnoresTheConfiguredDurationAndEnableFlag() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("admin.db"))) {
            DeathbanService service = service(storage, false, Duration.ofHours(1), DEATH);

            DeathbanEntry entry = service.banFor(BANNED, Duration.ofMinutes(20), "administrator", "administrator",
                    DEATH);

            assertEquals(DEATH.plus(Duration.ofMinutes(20)), entry.expiresAt());
            assertTrue(service.isBanned(BANNED), "an administrative ban works even when the feature is off");
            assertThrows(IllegalArgumentException.class,
                    () -> service.banFor(BANNED, Duration.ZERO, "administrator", "administrator", DEATH));
        }
    }

    @Test
    void purgeRemovesOnlyExpiredBans() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("purge.db"))) {
            DeathbanService service = service(storage, true, Duration.ofHours(1), DEATH);
            service.banFor(BANNED, Duration.ofHours(1), "zombie", "unknown", DEATH);
            service.banFor(OTHER, Duration.ofHours(1), "zombie", "unknown", DEATH.plus(Duration.ofHours(2)));

            assertEquals(2, new SqlDeathbanStore(storage.database(), TestDatabases.logSilently()).all().size());

            DeathbanService later = service(storage, true, Duration.ofHours(1), DEATH.plus(Duration.ofHours(2))
                    .plus(Duration.ofMinutes(30)));

            assertEquals(1, later.purgeExpired());
            assertFalse(later.isBanned(BANNED));
            assertTrue(later.isBanned(OTHER));
        }
    }

    @Test
    void clearRemovesADurableBan() {
        Path file = tempDir.resolve("clear.db");

        try (StorageService first = TestDatabases.open(file)) {
            service(first, true, Duration.ofHours(1), DEATH).ban(BANNED, "zombie", "unknown", DEATH);
        }

        try (StorageService reopened = TestDatabases.open(file)) {
            DeathbanService service = service(reopened, true, Duration.ofHours(1), DEATH);

            assertTrue(service.clear(BANNED));
            assertFalse(service.isBanned(BANNED));
        }

        try (StorageService finalOpen = TestDatabases.open(file)) {
            assertFalse(service(finalOpen, true, Duration.ofHours(1), DEATH).isBanned(BANNED));
        }
    }

    private static DeathbanService service(StorageService storage, boolean enabled, Duration duration, Instant now) {
        MedievalSettings settings = new MedievalSettings(
                new MedievalSettings.Deathban(enabled, duration),
                new MedievalSettings.Dimensions(false, false),
                new MedievalSettings.Siege(true,
                        new MedievalSettings.Siege.Machine(1000, 100, Duration.ofSeconds(4)),
                        new MedievalSettings.Siege.Machine(750, 150, Duration.ofSeconds(6))),
                new MedievalSettings.Territory(25, true));

        return new DeathbanService(() -> settings,
                new SqlDeathbanStore(storage.database(), TestDatabases.logSilently()),
                Clock.fixed(now, ZoneOffset.UTC));
    }
}
