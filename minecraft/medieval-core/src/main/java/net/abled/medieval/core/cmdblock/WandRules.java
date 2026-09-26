package net.abled.medieval.core.cmdblock;

import java.util.Objects;

/**
 * The activation rules for one command wand, decided by its mode.
 *
 * <p>These are the decisions the listener asks for and the ticker asks for; they are pure so they
 * are unit-tested without a server. The identity of the wand - the UUID that makes one fishing rod
 * different from every other fishing rod - lives beside it, because the two are bound together
 * everywhere the item is written or read.
 *
 * <h2>The redstone analogy, made explicit</h2>
 * A real command block chain needs a power source. Here the <em>owner's other wands</em> are the
 * power source: a repeating wand that is ticking, or an impulse wand that was just fired, emits a
 * signal to the wands registered in {@code chain} mode, which run their command on that signal.
 * A chain wand can also fire itself on right-click, but its ordinary job is to follow the others.
 */
public final class WandRules {

    private WandRules() {
    }

    /**
     * Whether the mode's command should run for one right-click.
     *
     * <p>Only a {@link WandTrigger.Trigger#CLICK} wand answers clicks at all: an always-active wand
     * ignores its owner's input entirely, which is what "always active" has always meant - the
     * toggle question does not even arise.
     *
     * @param mode     the wand's mode
     * @param isActive whether a repeating wand is currently toggled on - an impulse wand ignores it
     * @return true when this click should dispatch the wand's command now
     */
    public static boolean firesOnClick(WandMode.Mode mode, boolean isActive) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case IMPULSE -> true;
            // A repeating wand's click is a toggle, not a fire - the running is the ticker's job -
            // but the toggle itself still answers the click, so the owner can see it took.
            case REPEATING -> false;
            case CHAIN -> true;
        };
    }

    /**
     * Whether the mode's command should run on every tick the shared ticker takes.
     *
     * <p>Repeating wands tick when toggled on. Impulse and chain wands never tick on their own -
     * unless they are always-active, in which case the impulse wand ticks like a clock and the
     * chain wand keeps listening but also fires on every tick.
     */
    public static boolean ticksWhenActive(WandMode.Mode mode, boolean isActive, WandTrigger.Trigger trigger) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(trigger, "trigger");
        if (trigger == WandTrigger.Trigger.ALWAYS) {
            return true;
        }
        return mode == WandMode.Mode.REPEATING && isActive;
    }

    /**
     * Whether a signal from another wand should make this one fire.
     *
     * <p>Only chain wands listen. This is what keeps one owner's impulse click from re-firing
     * every other impulse wand they carry.
     */
    public static boolean firesOnSignal(WandMode.Mode mode) {
        Objects.requireNonNull(mode, "mode");
        return mode == WandMode.Mode.CHAIN;
    }

    /**
     * Whether a right-click should reach the wand at all.
     *
     * <p>An always-active wand refuses clicks: its behaviour is automatic, and letting a click
     * force one extra run would make the "always" a lie.
     */
    public static boolean answersClicks(WandTrigger.Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        return trigger == WandTrigger.Trigger.CLICK;
    }
}
