package net.abled.medieval.paper.combat;

import net.abled.medieval.core.combat.CombatRules;
import net.abled.medieval.core.combat.MeleeWeapon;
import net.abled.medieval.core.config.MedievalSettings;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Objects;
import java.util.Optional;

/**
 * Makes quick charge on a melee weapon actually speed the attack up.
 *
 * <p>Vanilla gives {@code quick_charge} a crossbow reload bonus and nothing else, so the name on a
 * sword means nothing. This listener turns it into a real attack-cooldown reduction on the weapon
 * families medieval combat uses: sword, axe, mace and trident. The number is a multiplier on the
 * weapon's own {@code attack_speed} attribute - a quarter off the wait at level I, half at level V
 * - so a heavy axe and a quick sword each get faster in proportion to what they already were, and
 * the value is decided by {@link CombatRules} in the core, not here.
 *
 * <h2>Why a transient modifier on the player</h2>
 * The attribute that decides the attack cooldown lives on the player, not on the item; an item's
 * own attribute entries replace the item's base speed and would fight the vanilla tooltip. A
 * transient {@link AttributeModifier} named after this plugin, added when a qualifying weapon is
 * held and removed when it is not, is the same mechanism the game itself uses for equipment
 * bonuses, is never written to the player data (so a crash mid-swing cannot leave a permanent
 * boost behind), and is removed automatically on death and respawn.
 *
 * <h2>Threads</h2>
 * Every event here fires on the tick thread and every call touches live inventory or attribute
 * state, so nothing is moved off it.
 */
public final class QuickChargeAttackSpeedListener implements Listener {

    /** Namespace of this plugin, reused in the modifier key so it cannot collide with another. */
    public static final String MODIFIER_NAMESPACE = "medieval";

    /** The modifier's key path; one per player is added and removed, never stacked. */
    public static final String MODIFIER_KEY = "quick_charge_melee_speed";

    private final org.bukkit.plugin.Plugin plugin;
    private final java.util.function.Supplier<MedievalSettings.Combat> combat;

    public QuickChargeAttackSpeedListener(org.bukkit.plugin.Plugin plugin,
                                          java.util.function.Supplier<MedievalSettings.Combat> combat) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combat = Objects.requireNonNull(combat, "combat");
    }

    /** A new slot is selected: the modifier now follows whatever the new item is. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack selected = player.getInventory().getItem(event.getNewSlot());
        // Applied on the next tick: the held-item event fires before the client has fully switched,
        // and the speed value read back below would still describe the old hand.
        plugin.getServer().getScheduler().runTask(plugin, () -> apply(player, selected));
    }

    /** Off-hand swapping, the other way a weapon enters the main hand. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> apply(player, event.getMainHandItem()));
    }

    /**
     * A joining player is armed once with an accurate modifier.
     *
     * <p>Remove-then-apply rather than nothing-at-all: the attribute survives neither logout nor
     * death, but a reload that changed the rule while the player was online would otherwise leave
     * the old value until the next weapon switch. Doing it here means a rule change on reload is
     * picked up at the latest when anyone rejoins.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> apply(player, mainHandOf(player)));
    }

    /** Defensive cleanup on logout, in case a future server version starts persisting it. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    /**
     * Makes the player's {@code attack_speed} agree with the weapon in their main hand.
     *
     * <p>Remove first, then add: the modifier is keyed, so re-adding the same key is refused and a
     * stale value would survive a changed quick-charge level.
     */
    public void apply(Player player, ItemStack held) {
        clear(player);
        if (held == null || held.getType() == Material.AIR) {
            return;
        }
        if (MeleeWeapon.of(keyPathOf(held)).isEmpty()) {
            return;
        }
        int level = held.getEnchantmentLevel(Enchantment.QUICK_CHARGE);
        Optional<Double> multiplier = CombatRules.meleeSpeedMultiplier(combat.get(), level);
        if (multiplier.isEmpty()) {
            return;
        }

        AttributeInstance speed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (speed == null) {
            return;
        }
        speed.addTransientModifier(new AttributeModifier(
                new org.bukkit.NamespacedKey(MODIFIER_NAMESPACE, MODIFIER_KEY),
                multiplier.get() - 1.0,
                AttributeModifier.Operation.ADD_SCALAR));
    }

    /** Removes the modifier when it is present; called far more often than it has work to do. */
    public void clear(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.ATTACK_SPEED);
        if (speed == null) {
            return;
        }
        // Keyed removal, not a scan: getModifier(Key) and removeModifier(Key) are exactly what the
        // keyed constructor pair is for, and they cannot remove anybody else's modifier by accident.
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(MODIFIER_NAMESPACE, MODIFIER_KEY);
        if (speed.getModifier(key) != null) {
            speed.removeModifier(key);
        }
    }

    private static ItemStack mainHandOf(Player player) {
        PlayerInventory inventory = player.getInventory();
        return inventory == null ? null : inventory.getItemInMainHand();
    }

    private static String keyPathOf(ItemStack item) {
        Material type = item.getType();
        return type.getKey().getKey();
    }
}
