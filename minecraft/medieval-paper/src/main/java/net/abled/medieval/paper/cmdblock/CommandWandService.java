package net.abled.medieval.paper.cmdblock;

import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.cmdblock.WandMode;
import net.abled.medieval.core.cmdblock.WandRules;
import net.abled.medieval.core.cmdblock.WandTrigger;
import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.StorageException;
import net.abled.medieval.core.storage.StorageLog;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The command wands that exist, and what they do when they fire.
 *
 * <h2>What is stored, and why it is not on the item</h2>
 * The wand's identity and mode live on the item (see {@link CommandWandFactory}); the command it
 * runs lives here, keyed by that identity, because a command line is too long for the item's
 * metadata to carry comfortably and because the owner may rebind it with {@code cmdblock set}
 * without re-crafting anything. A wand item with no stored command does nothing, which is the safe
 * failure: it cannot run an empty string, and it cannot run a command somebody else configured.
 *
 * <p>The store is SQLite-backed through the same {@link Database} the rest of the plugin uses, so
 * wands and their commands survive restarts; the in-memory view is kept up to date on every write.
 *
 * <h2>The redstone wiring, in server terms</h2>
 * Every wand has two axes, like a vanilla command block: the mode (impulse/repeating/chain) and
 * the trigger (needs-redstone/always-active). A needs-redstone wand waits for its owner: impulse
 * runs on right-click, repeating toggles on and off and ticks while on, chain fires on other
 * wands' signals. An always-active wand needs nobody: it runs on the shared interval from the
 * moment the server starts, and its trigger survives restarts because it is stored, not toggled.
 * Chain wands fire when any other wand fires or ticks - the emitting wand is the signal.
 *
 * <h2>Threads</h2>
 * All reads and writes go through the tick thread - the listeners fire there, and the ticker runs
 * there. The {@link ConcurrentHashMap} is only a guard against the store being read while a
 * reload swaps the map; nothing is expected to touch these fields from another thread.
 */
public final class CommandWandService {

    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS cmdblock_wands (
                id       TEXT PRIMARY KEY,
                mode     TEXT NOT NULL,
                trigger  TEXT NOT NULL DEFAULT 'CLICK',
                command  TEXT NOT NULL
            )""";

    private static final String SQL_FIND = "SELECT mode, command FROM cmdblock_wands WHERE id = ?";
    private static final String SQL_UPSERT = """
            INSERT INTO cmdblock_wands (id, mode, trigger, command) VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET mode = excluded.mode, trigger = excluded.trigger,
                                         command = excluded.command""";
    private static final String SQL_DELETE = "DELETE FROM cmdblock_wands WHERE id = ?";
    private static final String SQL_LIST = "SELECT id, mode, trigger, command FROM cmdblock_wands";

    /** How often a repeating wand runs its command while active. */
    public static final long REPEAT_INTERVAL_TICKS = 20L;

    private final Database database;
    private final StorageLog log;
    private final MedievalScheduler scheduler;
    private final MessageRenderer renderer;
    private final CommandWandFactory factory;

    /** The known wands, by id. Rebuilt from storage at startup; written through on every change. */
    private final Map<UUID, Stored> wands = new ConcurrentHashMap<>();
    /** The repeating wands an owner toggled on, keyed by wand id. */
    private final Map<UUID, Boolean> activeRepeats = new ConcurrentHashMap<>();

    private boolean ticking;

    public CommandWandService(Database database, StorageLog log, MedievalScheduler scheduler,
                              MessageRenderer renderer, CommandWandFactory factory) {
        this.database = Objects.requireNonNull(database, "database");
        this.log = Objects.requireNonNull(log, "log");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** One stored wand: its mode, trigger and the command it runs. */
    public record Stored(WandMode.Mode mode, WandTrigger.Trigger trigger, String command) {

        public Stored {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(command, "command");
        }
    }

    /**
     * Reads every stored wand and starts the repeating ticker.
     *
     * <p>A database that cannot be read does not stop the server: the wands are simply unknown
     * until the next restart, and the failure is logged. Repeating wands start inactive after a
     * restart - a toggle left running across an unattended restart is a hazard, not a convenience.
     */
    public void load() {
        // The table is owned here rather than by a migration: the service is the only reader and
        // writer, and CREATE TABLE IF NOT EXISTS makes the first run on an existing database
        // identical to a fresh install. Without this, the first wand binding on a server whose
        // database predates the feature would fail on a missing table.
        try {
            database.update(SQL_CREATE, net.abled.medieval.core.storage.SqlBinder.none());
        } catch (RuntimeException failure) {
            log.error("Could not create the cmdblock_wands table", failure);
        }

        try {
            database.queryMany(SQL_LIST, net.abled.medieval.core.storage.SqlBinder.none(), row -> {
                UUID id = java.util.UUID.fromString(row.getString("id"));
                WandMode.Mode mode = WandMode.parse(row.getString("mode")).orElse(WandMode.Mode.IMPULSE);
                WandTrigger.Trigger trigger = WandTrigger.parse(row.getString("trigger"))
                        .orElse(WandTrigger.Trigger.CLICK);
                return Map.entry(id, new Stored(mode, trigger, row.getString("command")));
            }).forEach(entry -> wands.put(entry.getKey(), entry.getValue()));
        } catch (RuntimeException failure) {
            log.error("Could not read the command wands; none will work until the next restart", failure);
        }

        if (!ticking && !wands.isEmpty()) {
            ticking = true;
            scheduler.runSyncRepeating(this::tick,
                    Duration.ofMillis(REPEAT_INTERVAL_TICKS * 50L),
                    Duration.ofMillis(REPEAT_INTERVAL_TICKS * 50L));
        }
    }

    /**
     * Registers a new wand, or rebinds an existing one, and stores it.
     *
     * @return the stored entry, so the caller can report what was written
     */
    public Stored register(UUID id, WandMode.Mode mode, String command) {
        return register(id, mode, WandTrigger.Trigger.CLICK, command);
    }

    /**
     * Registers a new wand with an explicit trigger, or rebinds an existing one, and stores it.
     *
     * @return the stored entry, so the caller can report what was written
     */
    public Stored register(UUID id, WandMode.Mode mode, WandTrigger.Trigger trigger, String command) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(command, "command");

        Stored stored = new Stored(mode, trigger, command);
        wands.put(id, stored);
        database.update(SQL_UPSERT, statement -> {
            statement.setString(1, id.toString());
            statement.setString(2, mode.name());
            statement.setString(3, trigger.name());
            statement.setString(4, command);
        });
        return stored;
    }

    /**
     * Switches a wand's mode in place, keeping its command and trigger.
     *
     * <p>Switching off of repeating drops the session toggle: a wand that comes back as impulse or
     * chain has no business remembering it was mid-clock. The item's stored mode byte is stale
     * after this, so the caller must refresh the held item through the factory.
     *
     * @return the new stored entry, or empty when the id is unknown
     */
    public Optional<Stored> setMode(UUID id, WandMode.Mode mode) {
        Objects.requireNonNull(mode, "mode");
        Stored current = stored(id).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        if (current.mode() == WandMode.Mode.REPEATING && mode != WandMode.Mode.REPEATING) {
            setActive(id, false);
        }
        Stored updated = register(id, mode, current.trigger(), current.command());
        return Optional.of(updated);
    }

    /**
     * Switches a wand's trigger in place, keeping its command and mode.
     *
     * <p>Switching to always-active drops the session toggle - the always-on state is a property
     * of the wand, not a toggle, so the two must not mix. Switching to needs-redstone leaves the
     * wand waiting for a click like a freshly made one.
     *
     * @return the new stored entry, or empty when the id is unknown
     */
    public Optional<Stored> setTrigger(UUID id, WandTrigger.Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        Stored current = stored(id).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        setActive(id, false);
        Stored updated = register(id, current.mode(), trigger, current.command());
        return Optional.of(updated);
    }

    /** The stored wand for an id, or empty when the id is unknown or the item carries no id. */
    public Optional<Stored> stored(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(wands.get(id));
    }

    /** Whether a repeating wand is toggled on. */
    public boolean isActive(UUID id) {
        return activeRepeats.getOrDefault(id, Boolean.FALSE);
    }

    /** Sets a repeating wand's toggle, in memory only - the state is session by design. */
    public void setActive(UUID id, boolean active) {
        if (active) {
            activeRepeats.put(id, Boolean.TRUE);
        } else {
            activeRepeats.remove(id);
        }
    }

    /**
     * One right-click on a wand item.
     *
     * <p>Impulse wands run now. Repeating wands toggle and report the new state. Chain wands run
     * now - they are their own click's power source when clicked directly - and also signal the
     * other chain wands. In every case the wand must be held by the owner, which the listener has
     * already checked.
     *
     * @return true when the click was used, so the vanilla use (casting a rod) is cancelled
     */
    public boolean use(UUID id, Player player) {
        Optional<Stored> found = stored(id);
        if (found.isEmpty()) {
            renderer.send(player, "cmdblock-unbound", true);
            return true;
        }

        Stored stored = found.get();
        // An always-active wand refuses clicks: its behaviour is automatic, and a forced extra run
        // would make the "always" a lie.
        if (!WandRules.answersClicks(stored.trigger())) {
            renderer.send(player, "cmdblock-always-active", true);
            return true;
        }

        switch (stored.mode()) {
            case IMPULSE -> {
                dispatch(stored.command(), player);
                signalChains(player);
            }
            case REPEATING -> {
                boolean nowActive = !isActive(id);
                setActive(id, nowActive);
                renderer.send(player, nowActive ? "cmdblock-repeat-on" : "cmdblock-repeat-off", true);
                if (nowActive) {
                    // One dispatch on activation, so the first effect arrives on the click and not a
                    // full interval later - what a redstone clock would do.
                    dispatch(stored.command(), player);
                    signalChains(player);
                }
            }
            case CHAIN -> {
                dispatch(stored.command(), player);
                signalChains(player);
            }
        }
        return true;
    }

    /**
     * Fires every chain wand registered in the store. This is the "redstone signal": a wand that
     * just fired or just started ticking sends its signal to all the chain wands, wherever they
     * are, because the ask wires the trigger to the item rather than to a block position.
     */
    private void signalChains(Player trigger) {
        for (Map.Entry<UUID, Stored> entry : wands.entrySet()) {
            Stored stored = entry.getValue();
            if (WandRules.firesOnSignal(stored.mode())) {
                dispatch(stored.command(), trigger);
            }
        }
    }

    /** One tick of the shared repeating task: run every wand whose rules say it fires now. */
    private void tick() {
        List<UUID> fired = new ArrayList<>();
        for (Map.Entry<UUID, Stored> entry : wands.entrySet()) {
            Stored stored = entry.getValue();
            if (WandRules.ticksWhenActive(stored.mode(), isActive(entry.getKey()), stored.trigger())) {
                dispatch(stored.command(), null);
                fired.add(entry.getKey());
            }
        }
        if (!fired.isEmpty()) {
            signalChains(null);
        }
    }

    /**
     * Runs one command as the console.
     *
     * <p>The trigger player is passed so the result can be reported to them; the command itself
     * never sees them, because the dispatch is the console's, exactly as the catalogue's command
     * runner does. A dispatch that throws is caught and reported - a console command in a repeating
     * wand runs every second, and an exception there must not kill the ticker.
     */
    private void dispatch(String command, Player feedback) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException failure) {
            log.error("A command wand's dispatch failed: " + command, failure);
            if (feedback != null) {
                renderer.send(feedback, "cmdblock-dispatch-failed", true);
            }
            return;
        }
        if (feedback != null) {
            renderer.send(feedback, "cmdblock-dispatched", Map.of("command", command), true);
        }
    }

    /** Whether any wand exists with this id; used to reject renaming tricks on /medieval cmdblock set. */
    public boolean isKnown(UUID id) {
        return id != null && wands.containsKey(id);
    }

    /** Whether the plugin currently knows any wand at all; used by the ticker start guard. */
    public boolean isEmpty() {
        return wands.isEmpty();
    }
}
