package net.abled.medieval.core.combat;

/**
 * The melee weapons the combat rules know about.
 *
 * <p>Kept in the core rather than derived from Bukkit at runtime so the rule that decides what a
 * "weapon" is is testable without a server, and so Bedrock-side tooling can ask the same question
 * through the platform adapter later. {@link #matches} takes the item's {@code NamespacedKey} path
 * ({@code iron_sword}, {@code mace}) rather than a Bukkit type, for the same reason.
 */
public enum MeleeWeapon {

    /** The vanilla sword family, from wooden through netherite. */
    SWORD("sword"),

    /** The axe family, including the golden axe and the netherite axe. */
    AXE("axe"),

    /** The mace. One word, no tier prefix. */
    MACE("mace"),

    /** The trident is a melee weapon too, and can be thrown - the rules here apply to both. */
    TRIDENT("trident");

    private final String keySuffix;

    MeleeWeapon(String keySuffix) {
        this.keySuffix = keySuffix;
    }

    /**
     * Keys that end in a weapon suffix without naming a weapon of that family.
     *
     * <p>The pickaxe is a miner's tool whose name happens to end in {@code axe}; the combat rules
     * govern weapons, so a tool that happens to share the last three letters is not a battle axe
     * no matter how it is enchanted. Anything new that trips the same trap is added here, with a
     * test naming it.
     */
    private static final String NON_WEAPON_SUFFIXES = "pickaxe";

    /**
     * Whether an item key names this weapon family.
     *
     * <p>Matched on the key's last path segment ({@code iron_sword} -> {@code sword}) rather than
     * the whole key, so every material tier matches without listing it. A suffix of {@code sword}
     * also matches a hypothetical {@code medieval_broadsword}, which is deliberate: the family
     * grows with the server's own items without a code change.
     */
    public boolean matches(String keyPath) {
        if (keyPath == null) {
            return false;
        }
        String key = keyPath.toLowerCase(java.util.Locale.ROOT);
        return key.endsWith(keySuffix) && !key.endsWith(NON_WEAPON_SUFFIXES);
    }

    /** The key suffix that identifies this family, for diagnostics and tests. */
    public String keySuffix() {
        return keySuffix;
    }

    /**
     * The weapon family of an item key, or empty when the key names no weapon this plugin governs.
     *
     * <p>Checked from the most specific family to the least: a future {@code swordaxe} would be a
     * sword by this rule, and declaring the order here keeps that choice explicit rather than
     * accidental.
     */
    public static java.util.Optional<MeleeWeapon> of(String keyPath) {
        for (MeleeWeapon weapon : values()) {
            if (weapon.matches(keyPath)) {
                return java.util.Optional.of(weapon);
            }
        }
        return java.util.Optional.empty();
    }
}
