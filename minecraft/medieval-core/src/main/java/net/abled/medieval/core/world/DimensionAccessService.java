package net.abled.medieval.core.world;

import net.abled.medieval.api.EventBus;
import net.abled.medieval.api.MedievalService;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.storage.StorageException;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The single authority on which dimensions players may enter.
 *
 * <p>Portal events fire constantly, so the answer is served from an in-memory snapshot and the
 * database is only touched when an administrator changes a gate. The snapshot is an immutable map
 * held in a volatile field: readers on the tick thread never see a half-updated map, and a writer
 * only replaces it once the change is durable.
 *
 * <h2>Why persisted state wins over configuration</h2>
 * {@code config.yml} supplies the <em>default</em> for a gate that has never been changed at
 * runtime. The first {@code /nether open} writes a row, and from then on the row decides - otherwise
 * an event that opened a dimension would silently close again on the next restart, which is exactly
 * the behaviour the persisted state exists to prevent. A gate with no stored row keeps following
 * configuration, so editing {@code config.yml} still works until someone overrides it, and
 * {@link #applyConfigDefaults()} re-reads both after a reload.
 *
 * <p>Changes are published through the {@link EventBus} as {@link DimensionAccessChanged}, so an
 * event, a GUI or an announcement reacts to a gate instead of being wired into it.
 */
public final class DimensionAccessService implements MedievalService {

    private final Supplier<MedievalSettings> settings;
    private final WorldStateRepository state;
    private final EventBus events;
    private final Consumer<String> warnings;

    /** Immutable snapshot; replaced wholesale so readers never observe a partial update. */
    private volatile Map<Dimension, Boolean> open = Map.of();

    public DimensionAccessService(Supplier<MedievalSettings> settings, WorldStateRepository state,
                                  EventBus events, Consumer<String> warnings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.state = Objects.requireNonNull(state, "state");
        this.events = Objects.requireNonNull(events, "events");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    @Override
    public String name() {
        return "dimensions";
    }

    /**
     * Reads every gate once, at startup.
     *
     * <p>A dimension with no stored row takes its configuration default and, deliberately, writes
     * nothing: an absent row is what makes it still follow configuration later. An unreadable
     * database degrades to the configuration default - the same state a fresh install has - instead
     * of preventing the server from starting, and the failure is reported to the caller's log.
     */
    public void load() {
        Map<Dimension, Boolean> loaded = new EnumMap<>(Dimension.class);
        try {
            for (Dimension dimension : Dimension.values()) {
                loaded.put(dimension, resolve(dimension));
            }
        } catch (StorageException failure) {
            warnings.accept("Could not read the dimension state; falling back to config.yml ("
                    + failure.getMessage() + ")");
            loaded.clear();
            for (Dimension dimension : Dimension.values()) {
                loaded.put(dimension, dimension.configuredOpen(settings.get()));
            }
        }
        this.open = Map.copyOf(loaded);
    }

    /** True when players may currently enter this dimension. */
    public boolean isOpen(Dimension dimension) {
        Objects.requireNonNull(dimension, "dimension");
        Boolean value = open.get(dimension);
        if (value != null) {
            return value;
        }
        // Reached only before load() has run. The configuration default is used rather than a
        // database read: this path is walked by portal handlers on the tick thread, and a storage
        // failure must never surface as an exception there.
        return dimension.configuredOpen(settings.get());
    }

    /**
     * Opens or closes a gate and records the decision durably.
     *
     * <p>May be called from an asynchronous task: it touches the database and an immutable
     * snapshot, never server state.
     *
     * @param actor who made the change, for the published event and the startup log
     * @return true when this call changed the state, false when it was already as requested
     */
    public synchronized boolean setOpen(Dimension dimension, boolean open, String actor) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(actor, "actor");

        if (isOpen(dimension) == open) {
            return false;
        }

        state.putBoolean(dimension.stateKey(), open);

        Map<Dimension, Boolean> updated = new EnumMap<>(Dimension.class);
        updated.putAll(this.open);
        updated.put(dimension, open);
        this.open = Map.copyOf(updated);

        events.publish(new DimensionAccessChanged(dimension, open, actor));
        return true;
    }

    /**
     * Re-applies configuration to every gate that was never changed at runtime, used after a
     * reload. Gates an administrator or an event has decided keep their stored value.
     */
    public void applyConfigDefaults() {
        Map<Dimension, Boolean> updated = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            updated.put(dimension, resolve(dimension));
        }
        this.open = Map.copyOf(updated);
    }

    /** Current state of every gate, in declaration order. */
    public Map<Dimension, Boolean> snapshot() {
        Map<Dimension, Boolean> ordered = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            ordered.put(dimension, isOpen(dimension));
        }
        return Map.copyOf(ordered);
    }

    /** True when this dimension has a stored row, and therefore no longer follows configuration. */
    public boolean isOverridden(Dimension dimension) {
        Objects.requireNonNull(dimension, "dimension");
        return stored(dimension).isPresent();
    }

    private boolean resolve(Dimension dimension) {
        return stored(dimension).orElseGet(() -> dimension.configuredOpen(settings.get()));
    }

    private Optional<Boolean> stored(Dimension dimension) {
        return state.get(dimension.stateKey())
                .map(value -> "true".equalsIgnoreCase(value.trim()));
    }
}
