package net.abled.medieval.core.cmdblock;

import java.util.Locale;
import java.util.Optional;

/**
 * The first of the two command-block axes: how the command runs.
 *
 * <p>Vanilla command blocks ask two questions - this one (impulse/repeating/chain) and "Needs
 * Redstone or Always Active", which {@link WandTrigger} answers. The mode decides <em>how</em> the
 * command runs; the trigger decides <em>what sets it off</em>.
 *
 * <p>Like {@code GameModes}, the parse rules live in the core with their own enum so they are
 * unit-tested without a server, and the platform layer maps the result onto whatever its
 * representation is - here a PDC byte, written once when the wand is made.
 *
 * <h2>What is accepted</h2>
 *
 * <ul>
 *   <li>The mode's own name: {@code impulse}, {@code repeating}, {@code chain}, case-insensitive.
 *       The numbers 1, 2 and 3 are accepted alongside, because {@code /medieval cmdblock} presents
 *       the three modes numbered in the order vanilla's command-block UI does.</li>
 *   <li>The short aliases {@code i}, {@code r}, {@code c}, and the British spelling
 *       {@code normal} for impulse, which is what a plain command block is usually called.</li>
 * </ul>
 *
 * <p>Anything else is rejected rather than guessed at: a wrong mode silently bound to a
 * console-running item is worse than an answer refused.
 */
public final class WandMode {

    private WandMode() {
    }

    /** The wand modes, in the order the command and the prompt number them. */
    public enum Mode {
        IMPULSE,
        REPEATING,
        CHAIN
    }

    private static final String[] NUMBER_ALIASES = {"1", "2", "3"};
    private static final String[][] WORD_ALIASES = {
            {"impulse", "normal", "i"},
            {"repeating", "repeat", "r"},
            {"chain", "c"}
    };

    /**
     * Parses a chat line into a wand mode.
     *
     * @param text the raw line as typed; surrounding whitespace is ignored
     * @return the mode, or empty when the line names none
     */
    public static Optional<Mode> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String answer = text.trim().toLowerCase(Locale.ROOT);

        for (int index = 0; index < NUMBER_ALIASES.length; index++) {
            if (NUMBER_ALIASES[index].equals(answer)) {
                return Optional.of(Mode.values()[index]);
            }
            for (String alias : WORD_ALIASES[index]) {
                if (alias.equals(answer)) {
                    return Optional.of(Mode.values()[index]);
                }
            }
        }
        return Optional.empty();
    }

    /** The mode's name as the messages print it, capitalised. */
    public static String displayName(Mode mode) {
        return switch (mode) {
            case IMPULSE -> "Impulse";
            case REPEATING -> "Repeating";
            case CHAIN -> "Chain";
        };
    }
}
