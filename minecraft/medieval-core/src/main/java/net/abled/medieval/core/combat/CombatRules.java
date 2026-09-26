package net.abled.medieval.core.combat;

import net.abled.medieval.core.config.MedievalSettings;

import java.util.Objects;
import java.util.Optional;

/**
 * The combat enchantment rules, decided from plain values.
 *
 * <p>This is the core's version of the three behaviours: it answers questions about item keys,
 * enchantment levels and damage numbers without ever seeing a server object. The Paper listeners
 * extract those values from live items and events and bring them here, which keeps every rule in
 * one place that unit tests can reach and every Bukkit detail in the platform module.
 *
 * <h2>The three rules</h2>
 * <ul>
 *   <li><strong>Quick charge on melee weapons</strong> shortens the attack cooldown by a fixed
 *   amount per level, computed as a multiplier on the weapon's own speed. Vanilla gives the
 *   enchantment a crossbow reload bonus and nothing more; this rule makes the name mean what it
 *   says on a blade too.</li>
 *   <li><strong>Piercing through shields</strong> lets an attack from a piercing weapon deal its
 *   damage to a blocking defender. Crossbow bolts already pierce bodies; this rule extends the
 *   idea to the shield in the defender's hand.</li>
 *   <li><strong>Infinity on consumables</strong> keeps the item when it is used: eaten, drunk,
 *   thrown or popped. Vanilla limits the enchantment to arrows; this rule lets it keep a
 *   wind charge, a golden apple, a totem, a ration.</li>
 * </ul>
 */
public final class CombatRules {

    /**
     * The cooldown reduction of one quick-charge level, as a fraction of the weapon's attack
     * cooldown: level I attacks 25% sooner, level V half as often again as the base weapon allows.
     *
     * <p>A fraction rather than an absolute number of ticks so a fast weapon and a slow one both
     * benefit proportionally, and so the value survives a server that ticks faster or slower than
     * twenty times a second.
     */
    public static final double REDUCTION_PER_LEVEL = 0.10;

    private CombatRules() {
    }

    /**
     * The attack-cooldown multiplier a held weapon should get, or empty when no rule applies.
     *
     * <p>Level I quarters the wait, level V cuts it by half; the reduction is capped so a very
     * high level - the catalogue hands out levels beyond what vanilla allows - can never push the
     * multiplier to zero and turn the weapon into a machine gun. A negative multiplier would be
     * nonsense in the attribute's own terms; the cap keeps every answer a legal one.
     *
     * @param quickChargeLevel the weapon's quick charge level, zero when it has none
     * @return the multiplier to apply to {@code attack_speed}, or empty when the rule is off or
     *         the level is not positive
     */
    public static Optional<Double> meleeSpeedMultiplier(MedievalSettings.Combat combat, int quickChargeLevel) {
        Objects.requireNonNull(combat, "combat");
        if (!combat.quickChargeSpeedsMelee() || quickChargeLevel <= 0) {
            return Optional.empty();
        }
        double multiplier = 1.0 - Math.min(quickChargeLevel * REDUCTION_PER_LEVEL, 0.9);
        return Optional.of(Math.max(multiplier, 0.1));
    }

    /**
     * Whether an attack from the given weapon key should ignore a shield.
     *
     * <p>Decided by the weapon family rather than by the item, so every tier of the family
     * behaves the same and a custom medieval sword registered under a new key still qualifies by
     * ending in {@code sword}.
     */
    public static boolean piercesShields(MedievalSettings.Combat combat, String weaponKeyPath) {
        Objects.requireNonNull(combat, "combat");
        if (!combat.piercingIgnoresShields()) {
            return false;
        }
        return MeleeWeapon.of(weaponKeyPath).isPresent();
    }

    /**
     * Whether an eaten or drunk item should be kept.
     *
     * <p>Potions are covered by their item key, which every effect shares: the rule preserves
     * the bottle, not the brew, so there is no per-effect decision to make.
     */
    public static boolean keepsConsumable(MedievalSettings.Combat combat, String itemKeyPath) {
        Objects.requireNonNull(combat, "combat");
        if (!combat.infinityPreservesConsumables()) {
            return false;
        }
        return Consumable.isKeyPath(itemKeyPath);
    }

    /**
     * Whether a thrown projectile item should be kept.
     *
     * <p>The wind charge is the vanilla case; the ender pearl is included because it is consumed
     * the same way and hurts the same way, and a medieval soldier with an infinite pearl is a
     * siege engineer rather than a cheat.
     */
    public static boolean keepsThrown(MedievalSettings.Combat combat, String itemKeyPath) {
        return keepsConsumable(combat, itemKeyPath);
    }

    /**
     * Whether a popping totem should be kept, which is its own switch because the totem is spent
     * in death rather than in use: an owner may want apples to last while totems stay mortal.
     */
    public static boolean keepsTotem(MedievalSettings.Combat combat, String itemKeyPath) {
        Objects.requireNonNull(combat, "combat");
        if (!combat.infinityKeepsTotem()) {
            return false;
        }
        return Consumable.TOTEM.keyPaths()[0].equals(
                itemKeyPath == null ? "" : itemKeyPath.toLowerCase(java.util.Locale.ROOT));
    }
}
