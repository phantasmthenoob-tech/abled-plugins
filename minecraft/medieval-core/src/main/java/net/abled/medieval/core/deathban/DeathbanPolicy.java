package net.abled.medieval.core.deathban;

import net.abled.medieval.api.MedievalService;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.storage.StorageException;
import net.abled.medieval.core.world.WorldStateRepository;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The live deathban rules: whether the feature is on, and how long a banishment lasts.
 *
 * <h2>Why this exists next to {@code config.yml}</h2>
 * {@code config.yml} supplies the <em>default</em> for both values. The first
 * {@code /medieval deathban off} (or {@code duration 30m}) writes a row and from then on the row
 * decides, so a switch an administrator flips during an event survives a restart - which is exactly
 * what a toggle is for. A value with no stored row keeps following configuration, so editing
 * {@code config.yml} still works until someone overrides it, and {@link #reset()} hands both values
 * back to the file. This is the same layering {@code DimensionAccessService} uses for the gates.
 *
 * <p>The two values are tracked and overridden independently: setting a duration must not freeze
 * the enable flag, so {@code /medieval deathban on|off} still works afterwards.
 *
 * <h2>Threads</h2>
 * Reads are served from fields for the tick thread - a death and a login both ask - and every write
 * is a single-row database statement, so a change may be made from an asynchronous task. The fields
 * are replaced only after the row is durable, using the same immutable-answer rule the gates use:
 * a reader never observes a half-applied change.
 *
 * <p>Nothing here is ever read from {@code config.yml} after startup, except through
 * {@link #applyConfigDefaults()} - which is why a reload can re-apply the file without touching a
 * value an administrator set at runtime.
 */
public final class DeathbanPolicy implements MedievalService {

    /** World-state keys. Kept here so the administrative commands and this service cannot drift. */
    public static final String KEY_ENABLED = "deathban.enabled";
    public static final String KEY_DURATION_SECONDS = "deathban.duration-seconds";

    /** Reported by the status command so it is visible where the live values came from. */
    public static final String SOURCE_CONFIG = "config.yml";
    public static final String SOURCE_RUNTIME = "runtime override";

    private final Supplier<MedievalSettings> settings;
    private final WorldStateRepository state;
    private final Consumer<String> warnings;

    private volatile boolean enabled;
    private volatile Duration duration;
    private volatile boolean enabledOverridden;
    private volatile boolean durationOverridden;

    public DeathbanPolicy(Supplier<MedievalSettings> settings, WorldStateRepository state,
                          Consumer<String> warnings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.state = Objects.requireNonNull(state, "state");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    @Override
    public String name() {
        return "deathban-policy";
    }

    /**
     * Reads both values once, at startup.
     *
     * <p>An unreadable database degrades to the configuration default - the same state a fresh
     * install has - instead of preventing the server from starting, and the failure is reported to
     * the caller's log rather than being swallowed. A malformed stored duration is treated the same
     * way: it is reported, ignored, and the configured value is used, because a bad number must not
     * be able to silently change how long players are banned.
     */
    public void load() {
        try {
            Optional<Boolean> storedEnabled = state.get(KEY_ENABLED)
                    .map(value -> "true".equalsIgnoreCase(value.trim()));
            Optional<Duration> storedDuration = storedDuration();

            apply(storedEnabled, storedDuration);
        } catch (StorageException failure) {
            warnings.accept("Could not read the deathban settings; following config.yml instead ("
                    + failure.getMessage() + ")");
            apply(Optional.empty(), Optional.empty());
        }
    }

    /** The live deathban rules, as the rest of the plugin should read them. */
    public MedievalSettings.Deathban rules() {
        return new MedievalSettings.Deathban(isEnabled(), duration());
    }

    /**
     * The gameplay settings with the deathban section replaced by the live values.
     *
     * <p>This is how the rule reaches {@link DeathbanService}, which reads its configuration through
     * a supplier: passing {@code () -> policy.apply(core.settings())} means the service sees the
     * effective rules without either class knowing about the other.
     */
    public MedievalSettings apply(MedievalSettings base) {
        Objects.requireNonNull(base, "base");
        // Every other section is carried through by reference: rebuilding the snapshot must not reset
        // a group this class knows nothing about (a convenience constructor here would silently drop
        // the search limits, for one).
        return new MedievalSettings(rules(), base.dimensions(), base.siege(), base.territory(),
                base.land(), base.admin());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration duration() {
        return duration;
    }

    /** True when either value came from a runtime command rather than from config.yml. */
    public boolean isOverridden() {
        return enabledOverridden || durationOverridden;
    }

    /** Where the live values came from, for the administrative status command. */
    public String source() {
        return isOverridden() ? SOURCE_RUNTIME : SOURCE_CONFIG;
    }

    /**
     * Turns the feature on or off and records the decision.
     *
     * @param actor who made the change, for the server log
     * @return true when this call changed the state, false when it was already as requested
     */
    public synchronized boolean setEnabled(boolean value, String actor) {
        Objects.requireNonNull(actor, "actor");

        // Compared against the effective value, so "on" on a server that is already on is reported
        // as unchanged (and writes no row) even when config.yml, not a runtime command, is what made
        // it on. Writing the row would only turn a config value into a frozen one for no reason.
        if (enabled == value) {
            return false;
        }

        state.putBoolean(KEY_ENABLED, value);
        this.enabled = value;
        this.enabledOverridden = true;
        return true;
    }

    /**
     * Sets how long a banishment lasts and records it.
     *
     * <p>Only affects bans written from now on: a stored ban keeps the expiry it was created with,
     * because its expiry is an absolute timestamp. Shortening the duration therefore never silently
     * releases someone who is already serving a ban - use {@code clear} for that.
     *
     * @return true when this call changed the value, false when it already matched
     */
    public synchronized boolean setDuration(Duration value, String actor) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(actor, "actor");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("deathban duration must be positive: " + value);
        }

        if (duration.equals(value)) {
            return false;
        }

        state.put(KEY_DURATION_SECONDS, Long.toString(value.toSeconds()));
        this.duration = value;
        this.durationOverridden = true;
        return true;
    }

    /**
     * Drops every runtime override so both values follow {@code config.yml} again.
     *
     * @return true when there was an override to remove
     */
    public synchronized boolean reset() {
        // Both removals are attempted, so a half-cleared pair cannot be left behind.
        boolean removed = state.remove(KEY_ENABLED);
        removed |= state.remove(KEY_DURATION_SECONDS);

        apply(Optional.empty(), Optional.empty());
        return removed;
    }

    /**
     * Re-applies configuration to any value that was never changed at runtime; used after a reload.
     * An overridden value keeps the stored decision.
     */
    public synchronized void applyConfigDefaults() {
        apply(storedBoolean(KEY_ENABLED), storedDuration());
    }

    private void apply(Optional<Boolean> storedEnabled, Optional<Duration> storedDuration) {
        MedievalSettings.Deathban configured = settings.get().deathban();
        this.enabled = storedEnabled.orElse(configured.enabled());
        this.duration = storedDuration.orElse(configured.duration());
        this.enabledOverridden = storedEnabled.isPresent();
        this.durationOverridden = storedDuration.isPresent();
    }

    private Optional<Boolean> storedBoolean(String key) {
        return state.get(key).map(value -> "true".equalsIgnoreCase(value.trim()));
    }

    private Optional<Duration> storedDuration() {
        Optional<String> stored = state.get(KEY_DURATION_SECONDS);
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        try {
            long seconds = Long.parseLong(stored.get().trim());
            if (seconds <= 0L) {
                warnings.accept("Ignoring the stored deathban duration " + stored.get()
                        + ": it must be positive; config.yml decides instead");
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException malformed) {
            warnings.accept("Ignoring the stored deathban duration '" + stored.get()
                    + "': it is not a number; config.yml decides instead");
            return Optional.empty();
        }
    }
}
