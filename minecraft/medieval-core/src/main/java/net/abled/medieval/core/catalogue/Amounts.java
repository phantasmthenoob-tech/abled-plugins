package net.abled.medieval.core.catalogue;

import java.util.Optional;

/**
 * Parsing rules for a take amount typed into chat.
 *
 * <p>A plain parser with no server dependency, so the rules are unit-tested without a running
 * server and the platform layer only hands over the strings. It is the amount equivalent of
 * {@link CatalogueQuery}: the GUI offers the quick picks, this is what happens when the player
 * would rather type.
 *
 * <h2>What is accepted</h2>
 *
 * <ul>
 *   <li>Digits only: {@code 1}, {@code 16}, {@code 1024} - no signs, no separators. The chat
 *       capture already hands over one trimmed line, and a {@code +} or a thousands comma is more
 *       likely a typo than an intent.</li>
 *   <li>Clamped to the item's stack size, which the caller supplies: the amount is what one click
 *       hands out, and it is handed out as one stack, so it cannot exceed it.</li>
 *   <li>{@code 0} and negatives are rejected rather than clamped to nothing: the amount button
 *       never offers a way to take nothing, and a silent zero would look like a click that did
 *       not work.</li>
 * </ul>
 */
public final class Amounts {

    private Amounts() {
    }

    /**
     * Parses a chat line into a take amount, clamped to {@code maxStackSize}.
     *
     * @param text         the raw line as typed; surrounding whitespace is ignored
     * @param maxStackSize the largest amount that can be handed out as one stack; at least 1
     * @return the amount, or empty when the line is not a usable amount
     */
    public static Optional<Integer> parse(String text, int maxStackSize) {
        if (text == null) {
            return Optional.empty();
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 9) {
            // Length first: parseUnsignedInt would happily hold the whole line for a pasted
            // paragraph before rejecting it, and nine digits already covers any stack size.
            return Optional.empty();
        }

        long value = 0;
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character < '0' || character > '9') {
                return Optional.empty();
            }
            value = value * 10 + (character - '0');
        }

        int ceiling = Math.max(1, maxStackSize);
        if (value < 1) {
            return Optional.empty();
        }
        return Optional.of((int) Math.min(value, ceiling));
    }
}
