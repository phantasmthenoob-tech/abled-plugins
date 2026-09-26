package net.abled.medieval.core.combat;

/**
 * The consumable item families the infinity rule preserves.
 *
 * <p>The rule reads: an item with {@code infinity} is not consumed on use. A wind charge is not
 * consumed when thrown, a golden apple is not eaten when eaten, a totem is not spent when it pops,
 * and a bowl, bottle or bucket comes back to the player on top.
 *
 * <p>Enumerated by family rather than one constant per material so a version that adds another
 * tier of apple, another fish or another stew is picked up by the same constant. Potion items are
 * deliberately absent: every potion shares one item key, so they are classified on their potion
 * type by the platform adapter instead, where the type is readable.
 */
public enum Consumable {

    /** Golden and enchanted golden apples, the battle food that also carries an effect. */
    APPLE("golden_apple", "enchanted_golden_apple"),

    /**
     * Every potion, splash and lingering, drunk or splashed. All potions of every effect share
     * one item key per kind, so the family is keyed on the item - the enchantment preserves the
     * bottle, whatever brew it carries.
     */
    POTION("potion", "splash_potion", "lingering_potion"),

    /** The ender pearl, a thrown consumable by the same rule as the wind charge. */
    PEARL("ender_pearl"),

    /** Chorus fruit, the teleporting snack. */
    CHORUS("chorus_fruit"),

    /** Stews and soups, which leave an empty bowl behind when eaten. */
    BOWL("mushroom_stew", "rabbit_stew", "suspicious_stew", "beetroot_soup"),

    /** Milk in a bucket, which leaves an empty bucket behind when drunk. */
    MILK("milk_bucket"),

    /** Honey bottled, which leaves an empty glass bottle behind when drunk. */
    HONEY("honey_bottle"),

    /** Cooked meats: the everyday medieval ration, steak above all. */
    COOKED_MEAT("cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken",
            "cooked_rabbit", "cooked_cod", "cooked_salmon"),

    /** Bread and the other staple baked foods. */
    BAKED("bread", "cookie", "pumpkin_pie", "baked_potato"),

    /** The totem of undying, which is spent by popping rather than by being eaten. */
    TOTEM("totem_of_undying"),

    /** The wind charge, thrown rather than eaten and spent by being thrown. */
    WIND_CHARGE("wind_charge");

    private final String[] keyPaths;

    Consumable(String... keyPaths) {
        this.keyPaths = keyPaths;
    }

    /** The item key paths this family covers. */
    public String[] keyPaths() {
        return keyPaths;
    }

    /**
     * Whether an item key path names a consumable this rule governs.
     *
     * <p>Potions are covered by their item key ({@code potion}, {@code splash_potion},
     * {@code lingering_potion}), which every effect shares: the rule preserves the bottle, not
     * the brew.
     */
    public static boolean isKeyPath(String keyPath) {
        if (keyPath == null) {
            return false;
        }
        String key = keyPath.toLowerCase(java.util.Locale.ROOT);
        for (Consumable family : values()) {
            for (String path : family.keyPaths) {
                if (path.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }
}
