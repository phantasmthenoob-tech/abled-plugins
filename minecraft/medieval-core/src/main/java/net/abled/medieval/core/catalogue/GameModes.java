package net.abled.medieval.core.catalogue;

import java.util.Locale;
import java.util.Optional;

/**
 * Parsing rules for a gamemode typed into chat.
 *
 * <p>The catalogue's gamemode button asks for a number, and this is what turns the answer into a
 * mode. It lives in the core with its own enum rather than beside the Bukkit type, for the same
 * reason {@link Amounts} does: the rules are unit-tested without a server, and the platform layer
 * maps the result onto whatever its mode type is.
 *
 * <h2>What is accepted</h2>
 *
 * <ul>
 *   <li>The numbers 1, 2 and 3: survival, creative, spectator - the order the button's hover text
 *       lists them in. Digits beyond that ({@code 0}, {@code 4}) are rejected rather than clamped,
 *       because a wrong mode silently applied is worse than an answer refused.</li>
 *   <li>The mode's own name, case-insensitive: {@code survival}, {@code CREATIVE}, {@code
 *       spectator}. A player who types the word meant the word; falling back to a number instead
 *       would switch someone into the wrong mode for the sake of being strict.</li>
 *   <li>The short aliases {@code s}, {@code c}, {@code sp}, which command users already know.</li>
 * </ul>
 *
 * <p>Adventure is deliberately unreachable: the catalogue's switcher is the owner's quick tool,
 * the three modes it names are the three the button promises, and a mode that is not on the button
 * should not be one typo away.
 */
public final class GameModes {

    private GameModes() {
    }

    /** The catalogue's modes, in the order the button and the prompt number them. */
    public enum Mode {
        SURVIVAL,
        CREATIVE,
        SPECTATOR
    }

    private static final String[] NUMBER_ALIASES = {"1", "2", "3"};
    private static final String[] WORD_ALIASES = {"survival", "creative", "spectator"};
    private static final String[] SHORT_ALIASES = {"s", "c", "sp"};

    /**
     * Parses a chat line into a gamemode.
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
            if (NUMBER_ALIASES[index].equals(answer)
                    || WORD_ALIASES[index].equals(answer)
                    || SHORT_ALIASES[index].equals(answer)) {
                return Optional.of(Mode.values()[index]);
            }
        }
        return Optional.empty();
    }
}
