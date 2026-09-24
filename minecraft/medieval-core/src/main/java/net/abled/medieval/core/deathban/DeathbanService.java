package net.abled.medieval.core.deathban;

import net.abled.medieval.api.MedievalService;
import net.abled.medieval.core.config.MedievalSettings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /**
     * Decides whether a player may join, and releases an expired ban on the way.
     *
     * <p>This is the single place that answers "may this player play?": the platform layer only
     * renders the {@link AccessDecision}. Behaviour:
     * <ul>
     *   <li>no stored ban -&gt; allowed;</li>
     *   <li>active ban and the feature is enabled -&gt; denied, carrying the remaining time;</li>
     *   <li>active ban but the feature was switched off by an administrator -&gt; allowed, so
     *       disabling deathban immediately lets everyone back in;</li>
     *   <li>expired ban -&gt; the row is deleted, so it cannot linger in storage.</li>
     * </ul>
     *
     * <p>Performs one point read and, only for an expired row, one delete. Both are small enough
     * to run on the asynchronous pre-login thread, which is where the platform calls this.
     */
    public AccessDecision checkLogin(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        Optional<DeathbanEntry> stored = store.find(playerId);
        if (stored.isEmpty()) {
            return AccessDecision.permit();
        }

        DeathbanEntry entry = stored.get();
        if (entry.isActive(now)) {
            return isEnabled() ? AccessDecision.denied(entry.remaining(now)) : AccessDecision.permit();
        }

        store.delete(playerId);
        return AccessDecision.permit();
    }

    /**
     * Stored bans that are still in effect, ordered by expiry, which is what the administrative list
     * shows. Expired rows are not included: they are pending the next purge, not in force.
     */
    public List<DeathbanEntry> activeBans() {
        Instant now = clock.instant();
        return store.all().stream().filter(entry -> entry.isActive(now)).toList();
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

    /**
     * Creates a ban with an explicit duration, ignoring the configured enable flag and duration.
     * Used by the administrative {@code set} command, which must work even when deathban is
     * disabled for normal play.
     */
    public DeathbanEntry banFor(UUID playerId, Duration duration, String cause, String killer, Instant deathAt) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(deathAt, "deathAt");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("ban duration must be positive: " + duration);
        }

        DeathbanEntry entry = new DeathbanEntry(playerId, deathAt, deathAt.plus(duration), cause, killer);
        store.save(entry);
        return entry;
    }

    /** Administrative override; returns true when an entry was removed. */
    public boolean clear(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return store.delete(playerId);
    }

    /**
     * Removes an already expired entry so it does not linger in storage.
     *
     * @return true when an expired row was removed
     */
    public boolean purgeIfExpired(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Instant now = clock.instant();
        return store.find(playerId)
                .filter(entry -> !entry.isActive(now))
                .map(entry -> store.delete(playerId))
                .orElse(false);
    }

    /** Removes expired entries; returns how many were purged. */
    public int purgeExpired() {
        return store.deleteExpired(clock.instant());
    }
}
