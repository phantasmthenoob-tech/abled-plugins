package net.abled.medieval.core.deathban;

import net.abled.medieval.api.MedievalService;
import net.abled.medieval.core.config.MedievalSettings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Deathban rules: record a death, deny access until expiry, release afterwards.
 *
 * <p>Settings are read through a supplier so a {@code /medieval reload} takes effect without
 * rebuilding the service. Time comes from a {@link Clock} so tests are deterministic.
 */
public final class DeathbanService implements MedievalService {

    private final Supplier<MedievalSettings> settings;
    private final DeathbanStore store;
    private final Clock clock;

    public DeathbanService(Supplier<MedievalSettings> settings, DeathbanStore store, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static DeathbanService withSystemClock(Supplier<MedievalSettings> settings, DeathbanStore store) {
        return new DeathbanService(settings, store, Clock.systemUTC());
    }

    @Override
    public String name() {
        return "deathban";
    }

    public boolean isEnabled() {
        return settings.get().deathban().enabled();
    }

    /** The active ban for a player, or empty when they are not banned. */
    public Optional<DeathbanEntry> activeBan(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        return store.find(playerId).filter(entry -> entry.isActive(now));
    }

    public boolean isBanned(UUID playerId) {
        return activeBan(playerId).isPresent();
    }

    /** Time until release, or empty when the player is not banned. */
    public Optional<Duration> remaining(UUID playerId) {
        Instant now = clock.instant();
        return activeBan(playerId).map(entry -> entry.remaining(now));
    }

    /**
     * Records a deathban. Returns empty when the feature is disabled, so callers cannot
     * accidentally ban players on a server that has deathban turned off.
     */
    public Optional<DeathbanEntry> ban(UUID playerId, String cause, String killer, Instant deathAt) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(deathAt, "deathAt");

        MedievalSettings.Deathban rules = settings.get().deathban();
        if (!rules.enabled()) {
            return Optional.empty();
        }

        DeathbanEntry entry = new DeathbanEntry(playerId, deathAt, deathAt.plus(rules.duration()), cause, killer);
        store.save(entry);
        return Optional.of(entry);
    }

    /** Administrative override; returns true when an entry was removed. */
    public boolean clear(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return store.delete(playerId);
    }

    /** Removes expired entries; returns how many were purged. */
    public int purgeExpired() {
        Instant now = clock.instant();
        int purged = 0;
        for (DeathbanEntry entry : store.all()) {
            if (!entry.isActive(now) && store.delete(entry.playerId())) {
                purged++;
            }
        }
        return purged;
    }
}
