package net.abled.medieval.core.deathban;

import net.abled.medieval.core.config.MedievalSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathbanServiceTest {

    private static final Instant DEATH = Instant.parse("2026-09-24T12:00:00Z");
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final InMemoryDeathbanStore store = new InMemoryDeathbanStore();
    private final AtomicReference<MedievalSettings> settings = new AtomicReference<>(enabledSettings(Duration.ofHours(1)));
    private final MutableClock clock = new MutableClock(DEATH);
    private final DeathbanService service = new DeathbanService(settings::get, store, clock);

    private static MedievalSettings enabledSettings(Duration duration) {
        return new MedievalSettings(
                new MedievalSettings.Deathban(true, duration),
                new MedievalSettings.Dimensions(false, false),
                new MedievalSettings.Siege(true,
                        new MedievalSettings.Siege.Machine(1000, 100, Duration.ofSeconds(4)),
                        new MedievalSettings.Siege.Machine(750, 150, Duration.ofSeconds(6))),
                new MedievalSettings.Territory(25, true));
    }

    @Test
    void recordsBanWithAbsoluteExpiry() {
        DeathbanEntry entry = service.ban(PLAYER, "zombie", "Herobrine", DEATH).orElseThrow();

        assertEquals(DEATH, entry.deathAt());
        assertEquals(DEATH.plus(Duration.ofHours(1)), entry.expiresAt());
        assertEquals("zombie", entry.cause());
        assertEquals("Herobrine", entry.killer());
        assertTrue(store.find(PLAYER).isPresent());
        assertTrue(service.isBanned(PLAYER));
    }

    @Test
    void storesUnknownAttributionWhenMissing() {
        DeathbanEntry entry = service.ban(PLAYER, null, "  ", DEATH).orElseThrow();

        assertEquals(DeathbanEntry.UNKNOWN, entry.cause());
        assertEquals(DeathbanEntry.UNKNOWN, entry.killer());
    }

    @Test
    void doesNothingWhenFeatureIsDisabled() {
        settings.set(new MedievalSettings(
                new MedievalSettings.Deathban(false, Duration.ofHours(1)),
                new MedievalSettings.Dimensions(false, false),
                enabledSettings(Duration.ofHours(1)).siege(),
                new MedievalSettings.Territory(25, true)));

        assertFalse(service.isEnabled());
        assertTrue(service.ban(PLAYER, "zombie", "unknown", DEATH).isEmpty());
        assertTrue(store.all().isEmpty());
        assertFalse(service.isBanned(PLAYER));
    }

    @Test
    void releasesPlayerAfterExpiry() {
        service.ban(PLAYER, "creeper", "unknown", DEATH);

        clock.set(DEATH.plus(Duration.ofMinutes(59)));
        assertTrue(service.isBanned(PLAYER));
        assertEquals(Duration.ofMinutes(1), service.remaining(PLAYER).orElseThrow());

        clock.set(DEATH.plus(Duration.ofHours(1)));
        assertFalse(service.isBanned(PLAYER));
        assertTrue(service.remaining(PLAYER).isEmpty());
    }

    @Test
    void remainingUsesTheInjectedClock() {
        service.ban(PLAYER, "fall", "unknown", DEATH);

        assertEquals(Duration.ofHours(1), service.remaining(PLAYER).orElseThrow());
        clock.advance(Duration.ofMinutes(30));
        assertEquals(Duration.ofMinutes(30), service.remaining(PLAYER).orElseThrow());
    }

    @Test
    void clearRemovesBanForAdminOverride() {
        service.ban(PLAYER, "fall", "unknown", DEATH);

        assertTrue(service.clear(PLAYER));
        assertFalse(service.isBanned(PLAYER));
        assertFalse(service.clear(PLAYER));
    }

    @Test
    void purgeExpiredRemovesOnlyExpiredEntries() {
        UUID other = UUID.randomUUID();
        service.ban(PLAYER, "fall", "unknown", DEATH);
        service.ban(other, "fall", "unknown", DEATH);
        clock.set(DEATH.plus(Duration.ofHours(2)));

        assertEquals(2, service.purgeExpired());
        assertTrue(store.all().isEmpty());
    }

    @Test
    void reloadAffectsDurationOfNewBansOnly() {
        service.ban(PLAYER, "fall", "unknown", DEATH);
        clock.set(DEATH.plus(Duration.ofMinutes(10)));

        settings.set(enabledSettings(Duration.ofMinutes(5)));
        DeathbanEntry shortened = service.ban(UUID.randomUUID(), "fall", "unknown", clock.instant()).orElseThrow();

        assertEquals(clock.instant().plus(Duration.ofMinutes(5)), shortened.expiresAt());
        assertTrue(service.isBanned(PLAYER), "existing bans keep their original expiry");
    }

    @Test
    void entryRemainingNeverGoesNegative() {
        DeathbanEntry entry = new DeathbanEntry(PLAYER, DEATH, DEATH.plusSeconds(60), "fall", "unknown");

        assertEquals(Duration.ZERO, entry.remaining(DEATH.plusSeconds(600)));
        assertFalse(entry.isActive(DEATH.plusSeconds(60)));
    }

    /** Test clock whose instant can be moved forward. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant newInstant) {
            this.instant = newInstant;
        }

        private void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MutableClock that && instant.equals(that.instant);
        }

        @Override
        public int hashCode() {
            return instant.hashCode();
        }
    }
}
