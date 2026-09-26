package net.abled.medieval.core.cmdblock;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The second command-block axis, after {@link WandMode}: what powers the wand.
 *
 * <p>Vanilla command blocks ask two questions - impulse/repeating/chain, and "Needs Redstone" or
 * "Always Active". The mode decides <em>how</em> the command runs; the trigger decides <em>what
 * sets it off</em>. A wand needs both.
 *
 * <h2>What is accepted</h2>
 *
 * <ul>
 *   <li>{@code click} - the wand waits for its owner. This is "Needs Redstone": impulse runs once
 *       per right-click, repeating waits to be toggled on, chain waits for another wand's signal.</li>
 *   <li>{@code always} - the wand runs by itself. Impulse and repeating wands tick on the shared
 *       interval from the moment the server starts, with no click and no toggle; the state survives
 *       restarts, because it is a property of the wand, not a session toggle.</li>
 *   <li>Aliases: {@code needs-redstone}, {@code redstone} and {@code trigger} for click; {@code
 *       always-active} and {@code active} for always.</li>
 * </ul>
 */
public final class WandTrigger {

    private WandTrigger() {
    }

    /** What sets the wand off. */
    public enum Trigger {
        /** "Needs Redstone": the wand waits for a click, a toggle or a chain signal. */
        CLICK,
        /** "Always Active": the wand runs on its own, from startup, with no input. */
        ALWAYS
    }

    private static final String[] CLICK_ALIASES = {"click", "needs-redstone", "redstone", "trigger"};
    private static final String[] ALWAYS_ALIASES = {"always", "always-active", "active"};

    /**
     * Parses a chat line into a trigger.
     *
     * @param text the raw line as typed; surrounding whitespace is ignored
     * @return the trigger, or empty when the line names none
     */
    public static Optional<Trigger> parse(String text) {
        Objects.requireNonNull(text, "text") ;
        String answer = text.trim().toLowerCase(Locale.ROOT);

        for (String alias : CLICK_ALIASES) {
            if (alias.equals(answer)) {
                return Optional.of(Trigger.CLICK);
            }
        }
        for (String alias : ALWAYS_ALIASES) {
            if (alias.equals(answer)) {
                return Optional.of(Trigger.ALWAYS);
            }
        }
        return Optional.empty();
    }

    /** The trigger's name as the messages print it, capitalised. */
    public static String displayName(Trigger trigger) {
        return trigger == Trigger.CLICK ? "Needs redstone" : "Always active";
    }
}
