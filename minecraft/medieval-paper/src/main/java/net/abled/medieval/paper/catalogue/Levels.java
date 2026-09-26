package net.abled.medieval.paper.catalogue;

import java.util.Optional;

/**
 * Parsing rules for an enchantment level typed into chat.
 *
 * <p>A plain text parser with no server dependency beyond naming it for the catalogue package, so
 * the rules are unit-tested without a running server. It is the level equivalent of
 * {@code Amounts} in the core: the picker's level buttons cycle the quick picks, this is what
 * happens when the player would rather type.
 *
 * <h2>What is accepted</h2>
 *
 * <ul>
 *   <li>Digits only, clamped to the enchantment's own maximum: {@code 2} on Sharpness V becomes 2,
 *       {@code 99} becomes 5. Clamping rather than rejecting keeps a typo from costing the whole
 *       prompt - the player asked for "as much as possible", and that is what they get.</li>
 *   <li>Roman numerals, case-insensitive: {@code II} and {@code ii} both mean 2, so an answer can
 *       read the way the preview prints it.</li>
 *   <li>Number words for the levels an enchantment usually has: one through ten.</li>
 * </ul>
 *
 * <p>{@code 0} and negatives are rejected: an enchantment button that ends up at level 0 is the
 * same as no enchantment, and the Clear button already means that explicitly.
 */
public final class Levels {

    private Levels() {
    }

    private static final String[] NUMERALS = {
            "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};

    private static final String[] WORDS = {
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};

    /**
     * Parses a chat line into an enchantment level, clamped to {@code maxLevel}.
     *
     * @param text     the raw line as typed; surrounding whitespace is ignored
     * @param maxLevel the enchantment's maximum level; at least 1
     * @return the level, or empty when the line is not a usable level
     */
    public static Optional<Integer> parse(String text, int maxLevel) {
        if (text == null) {
            return Optional.empty();
        }

        String trimmed = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > 9) {
            return Optional.empty();
        }

        // Words and numerals first: they are the shapes the preview itself prints.
        for (int index = 0; index < NUMERALS.length; index++) {
            if (NUMERALS[index].equals(trimmed)) {
                return clamp(index + 1, maxLevel);
            }
        }
        for (int index = 0; index < WORDS.length; index++) {
            if (WORDS[index].equals(trimmed)) {
                return clamp(index + 1, maxLevel);
            }
        }

        long value = 0;
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character < '0' || character > '9') {
                return Optional.empty();
            }
            value = value * 10 + (character - '0');
        }
        if (value < 1) {
            return Optional.empty();
        }
        return clamp((int) Math.min(value, Integer.MAX_VALUE), maxLevel);
    }

    private static Optional<Integer> clamp(int level, int maxLevel) {
        int ceiling = Math.max(1, maxLevel);
        return Optional.of(Math.min(level, ceiling));
    }
}
