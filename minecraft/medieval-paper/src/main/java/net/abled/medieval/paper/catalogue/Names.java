package net.abled.medieval.paper.catalogue;

import org.bukkit.Material;

import java.util.Locale;
import java.util.Objects;

/**
 * Naming helpers for the catalogue's chat round-trips.
 *
 * <p>Messages say <em>the search button</em> and <em>Take amount</em> rather than "slot 46" and
 * "slot 47", so the prompts and errors can name the thing the player should use. The labels come
 * from the same templates the buttons render, which means reworded buttons reword the prose with
 * them.
 *
 * <p>Item names are derived from the registry key rather than stored: {@code diamond_sword}
 * becomes {@code Diamond Sword}. The client's own display name is not used here because a chat
 * line built from a translatable component serialises to the translation key in the plain-text
 * form the chat capture sees - so the menu's pretty form and the chat form would disagree.
 */
public final class Names {

    private Names() {
    }

    /**
     * A human-readable item name: the registry key's last path segment, underscores split into
     * spaces, every word capitalised.
     */
    public static String of(Material material) {
        Objects.requireNonNull(material, "material");
        return prettify(material.getKey().getKey());
    }

    /** The bare key path, for matching and logs: {@code diamond_sword}. */
    public static String key(Material material) {
        return material.getKey().getKey();
    }

    /** The full registry id: {@code minecraft:diamond_sword}. */
    public static String id(Material material) {
        return material.getKey().toString();
    }

    /** Turns a key path such as {@code iron_nugget} into {@code Iron Nugget}. */
    public static String prettify(String keyPath) {
        String[] words = keyPath.toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder(keyPath.length() + 8);
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
        }
        return name.length() > 0 ? name.toString() : keyPath;
    }
}
