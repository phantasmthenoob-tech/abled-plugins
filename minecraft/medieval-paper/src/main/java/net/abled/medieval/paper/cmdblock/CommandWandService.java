package net.abled.medieval.paper.cmdblock;

import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.cmdblock.SqlWandStore;
import net.abled.medieval.core.cmdblock.WandBinding;
import net.abled.medieval.core.cmdblock.WandMode;
import net.abled.medieval.core.cmdblock.WandRules;
import net.abled.medieval.core.cmdblock.WandTrigger;
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
 * <h2>What is stored, and where</h2>
 * The wand's identity and mode live on the item (see {@link CommandWandFactory}); its binding -
 * mode, trigger and command - lives in the core's {@link SqlWandStore}, keyed by that identity,
 * because a command line is too long for item metadata to carry comfortably and because the owner
 * may rebind a wand without re-crafting anything. A wand item with no stored binding does nothing,
 * which is the safe failure: it cannot run an empty string, and it cannot run a command somebody
 * else configured.
 *
 * <p>The store creates its own table at construction, so the first binding on a server whose
 * database predates the feature works - the live failure this service once shipped with.
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
 * there. The {@link ConcurrentHashMap} guards the repeating toggles only; the store handles its
 * own thread-safety.
 */
public final class CommandWandService {

    /** How often a repeating wand runs its command while active. */
    public static final long REPEAT_INTERVAL_TICKS = 20L;

    private final SqlWandStore store;
    private final MedievalScheduler scheduler;
    private final MessageRenderer renderer;
    private final CommandWandFactory factory;
    private final java.util.function.Consumer<String> warnings;

    /** The repeating wands an owner toggled on, keyed by wand id. Session-only by design. */
    private final Map<UUID, Boolean> activeRepeats = new ConcurrentHashMap<>();

    private boolean ticking;

    public CommandWandService(SqlWandStore store, MedievalScheduler scheduler,
                              MessageRenderer renderer, CommandWandFactory factory,
                              java.util.function.Consumer<String> warnings) {
        this.store = Objects.requireNonNull(store, "store");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /**
     * Reads every stored wand and starts the repeating ticker.
     *
     * <p>Repeating wands start inactive after a restart - a toggle left running across an
     * unattended restart is a hazard, not a convenience. An always-active wand needs no toggle: it
     * runs from the first tick, because its trigger is a stored property, not a session state.
     */
    public void load() {
        store.load();

        if (!ticking && !store.all().isEmpty()) {
            ticking = true;
            scheduler.runSyncRepeating(this::tick,
                    Duration.ofMillis(REPEAT_INTERVAL_TICKS * 50L),
                    Duration.ofMillis(REPEAT_INTERVAL_TICKS * 50L));
        }
    }

    /**
     * Registers a new wand with an explicit trigger, or rebinds an existing one, and stores it.
     *
     * @return the binding as written
     */
    public WandBinding register(UUID id, WandMode.Mode mode, WandTrigger.Trigger trigger, String command) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(command, "command");

        return store.save(id, new WandBinding(mode, trigger, command));
    }

    /** The stored binding for an id, or empty when the id is unknown. */
    public Optional<WandBinding> stored(UUID id) {
        return store.find(id);
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
     * Switches a wand's mode in place, keeping its command and trigger.
     *
     * <p>Switching off of repeating drops the session toggle: a wand that comes back as impulse or
     * chain has no business remembering it was mid-clock. The item's stored mode byte is stale
     * after this, so the caller must refresh the held item through the factory.
     *
     * @return the new binding, or empty when the id is unknown
     */
    public Optional<WandBinding> setMode(UUID id, WandMode.Mode mode) {
        Objects.requireNonNull(mode, "mode");
        WandBinding current = store.find(id).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        if (current.mode() == WandMode.Mode.REPEATING && mode != WandMode.Mode.REPEATING) {
            setActive(id, false);
        }
        return Optional.of(store.save(id, new WandBinding(mode, current.trigger(), current.command())));
    }

    /**
     * Switches a wand's trigger in place, keeping its command and mode.
     *
     * <p>Switching to always-active drops the session toggle - the always-on state is a property
     * of the wand, not a toggle, so the two must not mix. Switching to needs-redstone leaves the
     * wand waiting for a click like a freshly made one.
     *
     * @return the new binding, or empty when the id is unknown
     */
    public Optional<WandBinding> setTrigger(UUID id, WandTrigger.Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        WandBinding current = store.find(id).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        setActive(id, false);
        return Optional.of(store.save(id, new WandBinding(current.mode(), trigger, current.command())));
    }

    /**
     * One right-click on a wand item.
     *
     * <p>An always-active wand refuses clicks: its behaviour is automatic, and a forced extra run
     * would make the "always" a lie. Otherwise impulse and chain wands run now; a repeating wand's
     * click is a toggle, and its first dispatch comes on activation - what a redstone clock would
     * do.
     *
     * @return true when the click was used, so the vanilla use (casting a rod) is cancelled
     */
    public boolean use(UUID id, Player player) {
        Optional<WandBinding> found = stored(id);
        if (found.isEmpty()) {
            renderer.send(player, "cmdblock-unbound", true);
            return true;
        }

        WandBinding binding = found.get();
        if (!WandRules.answersClicks(binding.trigger())) {
            renderer.send(player, "cmdblock-always-active", true);
            return true;
        }

        switch (binding.mode()) {
            case REPEATING -> {
                boolean nowActive = !isActive(id);
                setActive(id, nowActive);
                renderer.send(player, nowActive ? "cmdblock-repeat-on" : "cmdblock-repeat-off", true);
                if (nowActive) {
                    // One dispatch on activation, so the first effect arrives on the click and not a
                    // full interval later - what a redstone clock would do.
                    dispatch(binding.command(), player);
                    signalChains();
                }
            }
            case IMPULSE, CHAIN -> {
                dispatch(binding.command(), player);
                signalChains();
            }
        }
        return true;
    }

    /**
     * Fires every chain wand in the store. This is the "redstone signal": a wand that just fired
     * or just started ticking sends its signal to all the chain wands, wherever they are, because
     * the ask wires the trigger to the item rather than to a block position.
     */
    private void signalChains() {
        for (Map.Entry<UUID, WandBinding> entry : store.all().entrySet()) {
            if (WandRules.firesOnSignal(entry.getValue().mode())) {
                dispatch(entry.getValue().command(), null);
            }
        }
    }

    /** One tick of the shared repeating task: run every wand whose rules say it fires now. */
    private void tick() {
        boolean anyFired = false;
        for (Map.Entry<UUID, WandBinding> entry : store.all().entrySet()) {
            WandBinding binding = entry.getValue();
            if (WandRules.ticksWhenActive(binding.mode(), isActive(entry.getKey()), binding.trigger())) {
                dispatch(binding.command(), null);
                anyFired = true;
            }
        }
        if (anyFired) {
            signalChains();
        }
    }

    /**
     * Runs one command as the console.
     *
     * <p>On a click, the command is wrapped in {@code execute as <player> at <player> run ...}:
     * the console keeps its permissions, but the command's executor and position become the
     * owner, so {@code @s}, {@code ~ ~ ~} and functions that use them resolve to the player who
     * clicked - the same context a command block gets from {@code execute as @p at @s run ...}.
     * Dispatched bare, {@code @s} is the console, which is not an entity, so such commands fail
     * silently. Automated runs (repeating ticks, chain signals) have no clicking player, so they
     * dispatch with the console as their own context, where entity-relative syntax does not apply.
     *
     * <p>The trigger player is passed so the result can be reported to them; the command itself
     * never sees them, because the dispatch is the console's, exactly as the catalogue's command
     * runner does. A dispatch that throws is caught and reported - a console command in a repeating
     * wand runs every second, and an exception there must not kill the ticker.
     */
    private void dispatch(String command, Player feedback) {
        String effective = feedback != null
                ? "execute as " + feedback.getName() + " at " + feedback.getName() + " run " + command
                : command;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), effective);
        } catch (RuntimeException failure) {
            // The full detail goes to the server log; the player gets the short version.
            warnings.accept("A command wand's dispatch failed for '" + command + "': " + failure);
            if (feedback != null) {
                renderer.send(feedback, "cmdblock-dispatch-failed", true);
            }
            return;
        }
        if (feedback != null) {
            renderer.send(feedback, "cmdblock-dispatched", Map.of("command", command), true);
        }
    }
}
