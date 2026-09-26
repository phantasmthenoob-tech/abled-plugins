package net.abled.medieval.paper.combat;

import net.abled.medieval.core.combat.CombatRules;
import net.abled.medieval.core.config.MedievalSettings;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * Lets piercing go through shields.
 *
 * <p>Vanilla {@code piercing} adds bolts that pass through bodies and gates; the shield in the
 * defender's hand still stops every one of them. This listener finishes the idea: an attack made
 * with a piercing weapon - sword, axe, mace or trident, the same families the quick charge rule
 * uses - deals its damage to a blocking defender instead of being absorbed. Crossbow bolts with
 * the enchantment pierce shields on the same principle, arriving exactly as they would against an
 * unshielded target.
 *
 * <h2>Why the damage modifiers</h2>
 * The blocking reduction is one entry in the event's damage-modifier chain: vanilla computes it
 * as a negative value that cancels the shield-eligible part of the hit, then armour, resistance
 * and absorption run after it. Editing the {@code BLOCKING} modifier to zero rather than deleting
 * the entry means every modifier computed after blocking still sees an honest, untouched chain -
 * the hit is reduced by armour exactly as a hit that was never blocked would be. The event is
 * answered at {@code HIGHEST} so a later-running protection plugin can still cancel the hit
 * outright, which is the one thing it should be able to do that this rule does not override.
 *
 * <p>That modifier API is marked deprecated, and this listener uses it anyway, on purpose: a
 * decade of "will be removed soon" later it is still the only server API that exposes how much
 * of a hit a shield actually swallowed, and every shield-manipulating plugin works through it.
 * If a future server really does remove it, the compile breaks here loudly rather than the rule
 * silently half-working - and the {@code isApplicable} guard means a runtime drop of the entry
 * degrades to the fallback branch below instead of throwing.
 *
 * <p>The weapon is identified from the event, not guessed: a melee swing carries the weapon in
 * the damager's hand, and a bolt carries the crossbow that fired it. A player who attacks with a
 * fist, a fishing rod or a wind charge is not piercing anything, whatever enchantments they own.
 *
 * <h2>Threads</h2>
 * Damage events fire on the tick thread; everything here reads live entity and item state.
 */
public final class PiercingShieldListener implements Listener {

    private final java.util.function.Supplier<MedievalSettings.Combat> combat;

    public PiercingShieldListener(java.util.function.Supplier<MedievalSettings.Combat> combat) {
        this.combat = Objects.requireNonNull(combat, "combat");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player defender) || !defender.isBlocking()) {
            return;
        }
        // The dead and the indestructible have no use for either answer; armour stands are not
        // duellists, and a defender who is already being taken out of the world by another rule
        // does not need the shield-break applied on top.
        if (defender.isDead() || defender instanceof ArmorStand) {
            return;
        }

        String weaponKey = weaponKeyOf(event.getDamager());
        if (weaponKey == null) {
            return;
        }
        if (!CombatRules.piercesShields(combat.get(), weaponKey)) {
            return;
        }

        if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
            // The blocking entry is negative - it is the part of the hit the shield swallowed. Zero
            // it rather than removing it: the chain after it stays computed and consistent.
            event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0.0);
        } else {
            // The modifier is not present, which is how the event says the shield swallowed
            // everything: restore the full hit, then, so a fully blocked attack from a mace does
            // as much damage as a partially blocked one.
            event.setDamage(event.getDamage() + event.getOriginalDamage(
                    EntityDamageEvent.DamageModifier.BLOCKING));
        }
    }

    /**
     * The key path of the item the attack was made with, or null when the attack has no weapon
     * this rule can name: a melee attacker's main hand, a projectile's shooter's main hand for a
     * thrown trident, or a bolt's bow item for a shot crossbow.
     */
    private static String weaponKeyOf(org.bukkit.entity.Entity damager) {
        if (damager instanceof AbstractArrow arrow) {
            ItemStack bow = arrow.getWeapon();
            return bow == null ? null : bow.getType().getKey().getKey();
        }
        if (damager instanceof Player attacker) {
            ItemStack held = attacker.getInventory().getItemInMainHand();
            return held.getType().getKey().getKey();
        }
        // Skeleton arrows, snowballs, mobs and everything else: no weapon of ours.
        return null;
    }
}
