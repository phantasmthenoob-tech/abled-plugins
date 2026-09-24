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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login gate: what an administrator, a player and the database see when a banned player tries
 * to join. These are the rules the Paper pre-login listener renders, tested without a server.
 */
class DeathbanAccessTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant DEATH = Instant.parse("2026-09-24T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void allowsAPlayerWithNoStoredBan() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("clean.db"))) {
            AccessDecision decision = service(storage, true, DEATH).checkLogin(PLAYER);

            assertTrue(decision.allowed());
            assertEquals(Duration.ZERO, decision.remaining());
            assertFalse(decision.denied());
        }
    }

    @Test
    void deniesAnActiveBanAndReportsTheRemainingTime() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("denied.db"))) {
            Instant later = DEATH.plus(Duration.ofMinutes(20));
            DeathbanService service = service(storage, true, later);
            service.ban(PLAYER, "combat", "Herobrine", DEATH);

            AccessDecision decision = service.checkLogin(PLAYER);

            assertTrue(decision.denied());
            assertEquals(Duration.ofMinutes(40), decision.remaining());
        }
    }

    @Test
    void releasesAnExpiredBanAndRemovesTheRow() {
        Path file = tempDir.resolve("expired-row.db");

        try (StorageService first = TestDatabases.open(file)) {
            service(first, true, DEATH.plus(Duration.ofHours(6))).ban(PLAYER, "fall", "unknown", DEATH);
        }

        try (StorageService reopened = TestDatabases.open(file)) {
            DeathbanService service = service(reopened, true, DEATH.plus(Duration.ofHours(2)));

            assertTrue(service.checkLogin(PLAYER).allowed(), "an expired ban must not block the login");
            assertTrue(new SqlDeathbanStore(reopened.database(), TestDatabases.logSilently())
                    .find(PLAYER).isEmpty(), "the expired row must not be left behind");
        }
    }

    @Test
    void disablingTheFeatureReleasesPlayersBeforeTheirBanExpires() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("disabled.db"))) {
            DeathbanService enabled = service(storage, true, DEATH);
            enabled.banFor(PLAYER, Duration.ofDays(3), "administrator", "administrator", DEATH);

            DeathbanService disabled = service(storage, false, DEATH.plus(Duration.ofMinutes(5)));

            assertTrue(disabled.checkLogin(PLAYER).allowed(), "a switched-off feature must not keep players out");
            assertFalse(new SqlDeathbanStore(storage.database(), TestDatabases.logSilently())
                    .find(PLAYER).isEmpty(), "the stored ban is kept, not silently deleted");
        }
    }

    @Test
    void listsOnlyActiveBansOrderedByExpiry() {
        try (StorageService storage = TestDatabases.open(tempDir.resolve("active.db"))) {
            DeathbanService service = service(storage, true, DEATH);
            service.banFor(OTHER, Duration.ofHours(6), "combat", "Alex", DEATH);
            service.banFor(PLAYER, Duration.ofHours(1), "fall", "unknown", DEATH);

            List<UUID> active = service.activeBans().stream().map(DeathbanEntry::playerId).toList();
            assertEquals(List.of(PLAYER, OTHER), active, "soonest release first");

            DeathbanService later = service(storage, true, DEATH.plus(Duration.ofHours(2)));
            assertTrue(later.checkLogin(PLAYER).allowed());
            assertEquals(List.of(OTHER), later.activeBans().stream().map(DeathbanEntry::playerId).toList());
        }
    }

    @Test
    void rejectsAccessDecisionsThatContradictThemselves() {
        assertThrows(IllegalArgumentException.class, () -> new AccessDecision(true, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new AccessDecision(false, Duration.ZERO));
        assertEquals(Duration.ofMinutes(5), AccessDecision.denied(Duration.ofMinutes(5)).remaining());
    }

    private static DeathbanService service(StorageService storage, boolean enabled, Instant now) {
        MedievalSettings settings = new MedievalSettings(
                new MedievalSettings.Deathban(enabled, Duration.ofHours(1)),
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
