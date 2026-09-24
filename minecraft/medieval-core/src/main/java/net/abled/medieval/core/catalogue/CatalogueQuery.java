package net.abled.medieval.core.catalogue;

import java.util.Objects;

/**
 * Matching rules for the catalogue's search box.
 *
 * <p>Deliberately a plain text matcher with no server dependency, so the rules are unit-tested
 * without a running server and the platform layer only has to supply the strings to compare.
 *
 * <h2>What counts as a match</h2>
 * Both the query and the candidate are normalised - lower case, and {@code _}, {@code -} and
 * whitespace all treated as a single space - and then every term of the query must appear somewhere
 * in the candidate. That makes the obvious searches work the way a player expects:
 *
 * <ul>
 *   <li>{@code sword} matches {@code diamond_sword} and {@code netherite_sword};</li>
 *   <li>{@code diamond sword} matches {@code diamond_sword}, because the id normalises to
 *       {@code diamond sword};</li>
 *   <li>{@code block command} also matches {@code command_block}, because the terms are required
 *       to be present rather than to be in order.</li>
 * </ul>
 *
 * <p>A blank query matches nothing rather than everything: an accidental empty search should show
 * an empty result, not the entire item registry.
 */
public final class CatalogueQuery {

    private CatalogueQuery() {
    }

    /**
     * Lower-cases the text and folds {@code _}, {@code -} and whitespace into single spaces, so
     * {@code DIAMOND_SWORD}, {@code diamond-sword} and {@code diamond  sword} all become
     * {@code diamond sword}.
     *
     * <p>Case folding is done per character rather than through {@code String.toLowerCase}: item
     * ids are ASCII, and this keeps the result independent of the server's default locale, which is
     * a real trap on a server started with a Turkish locale.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }

        StringBuilder normalized = new StringBuilder(text.length());
        boolean separatorPending = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '_' || character == '-' || Character.isWhitespace(character)) {
                // Collapsed: runs of separators, and separators at either end, produce one space
                // at most, so "  diamond__sword  " becomes "diamond sword".
                separatorPending = normalized.length() > 0;
                continue;
            }
            if (separatorPending) {
                normalized.append(' ');
                separatorPending = false;
            }
            normalized.append(Character.toLowerCase(character));
        }
        return normalized.toString();
    }

    /**
     * True when the query matches any of the candidates.
     *
     * @param query      what the player typed
     * @param candidates the strings to compare against - item id and enum name, typically
     */
    public static boolean matches(String query, String... candidates) {
        Objects.requireNonNull(candidates, "candidates");

        String wanted = normalize(query);
        if (wanted.isEmpty()) {
            return false;
        }

        for (String candidate : candidates) {
            String text = normalize(candidate);
            if (!text.isEmpty() && containsAllTerms(text, wanted)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAllTerms(String text, String normalizedQuery) {
        for (String term : normalizedQuery.split(" ")) {
            if (!text.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
