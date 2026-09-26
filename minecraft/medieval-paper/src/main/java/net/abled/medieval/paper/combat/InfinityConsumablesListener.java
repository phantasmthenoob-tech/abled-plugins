package net.abled.medieval.paper.combat;

import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.combat.CombatRules;
import net.abled.medieval.core.config.MedievalSettings;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Makes infinity work on consumables: eaten food, drunk potions, thrown charges, popping totems.
 *
 * <p>Vanilla ties {@code infinity} to arrows and nothing else, which leaves the medieval soldier
 * carrying a sack of steaks into a siege. This listener reinterprets the enchantment as
 * preservation: an item that carries it comes back after it is used, exactly as an arrow comes
 * back to an infinite bow. What "used" means depends on the item, and each kind is answered by
 * the hook that owns it:
 *
 * <ul>
 *   <li><strong>Eaten or drunk</strong> - the consume event's replacement is set to the same
 *   stack, so the item survives with its enchantments while its effects still apply.</li>
 *   <li><strong>Thrown</strong> (wind charge, ender pearl) - the launch event is told the item is
 *   not consumed.</li>
 *   <li><strong>Shot from a bow</strong> - the shoot event is told not to consume the arrow,
 *   which is also where vanilla arrow infinity keeps working.</li>
 *   <li><strong>Popping totem</strong> - the resurrect event is watched, and the totem is handed
 *   back a tick later, once the game has actually taken it.</li>
 * </ul>
 *
 * <p>Potions are covered by their item key, which every effect shares: the rule preserves the
 * bottle, not the brew. How much preservation applies is decided by {@link CombatRules} in the
 * core; this class only extracts values from live events and carries the answers out.
 *
 * <h2>Why the totem needs a small map</h2>
 * A totem is not "used" in the ordinary sense: the game removes it from the hand as it pops, and
 * the resurrect event carries no copy of it. The stack is remembered here the instant the event
 * fires and handed back once the death processing has finished - a tick later, on the tick thread.
 * The map is written and read on the tick thread only, drained rather than overwritten so two
 * totems popping close together are both restored, and cleared when the player leaves or dies
 * again before the restore ran.
 *
 * <h2>Threads</h2>
 * Every event here fires on the tick thread. The one delayed task - the totem restore - goes
 * through the platform scheduler and runs on the tick thread too, by contract.
 */
public final class InfinityConsumablesListener implements Listener {

    /** The game takes the totem during the death processing that follows the event. */
    private static final long TOTEM_RESTORE_DELAY_TICKS = 1L;

    private final java.util.function.Supplier<MedievalSettings.Combat> combat;
    private final MedievalScheduler scheduler;
    private final Map<UUID, List<ItemStack>> totemsInPlay = new HashMap<>();

    public InfinityConsumablesListener(java.util.function.Supplier<MedievalSettings.Combat> combat,
                                       MedievalScheduler scheduler) {
        this.combat = Objects.requireNonNull(combat, "combat");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Eaten food and drunk potions survive when they carry infinity.
     *
     * <p>The replacement is set to the same stack rather than the event cancelled, so the
     * consumption itself happens: a golden apple still grants its effects, a potion still applies
     * its brew, the milk still clears, and the item stays in the hand. Bowl, bottle and bucket
     * are replaced by the full stack too, so a stew keeps its bowl exactly as infinity would want.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !hasInfinity(item)) {
            return;
        }
        if (!CombatRules.keepsConsumable(combat.get(), item.getType().getKey().getKey())) {
            return;
        }

        event.setReplacement(item.clone());
    }

    /**
     * Thrown consumables - the wind charge, the ender pearl - are not spent.
     *
     * <p>The launch event's own consume flag is the hook the game offers for exactly this, so the
     * throw happens and the item stays in the hand.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event) {
        ItemStack item = event.getItemStack();
        if (item == null || !hasInfinity(item)) {
            return;
        }
        if (!CombatRules.keepsThrown(combat.get(), item.getType().getKey().getKey())) {
            return;
        }
        event.setShouldConsume(false);
    }

    /**
     * An arrow shot from an infinite bow is not consumed - including the vanilla case.
     *
     * <p>This handler answers only when the rule is on, so switching
     * {@code combat.infinity-preserves-consumables} off in config.yml returns the bow to vanilla
     * behaviour exactly, arrow for arrow.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player) || !event.getConsumeArrow()) {
            return;
        }
        ItemStack bow = event.getBow();
        if (bow == null || bow.getEnchantmentLevel(Enchantment.INFINITY) <= 0) {
            return;
        }
        if (!CombatRules.keepsConsumable(combat.get(), bow.getType().getKey().getKey())) {
            return;
        }
        event.setConsumeArrow(false);
    }

    /**
     * A popping totem with infinity is handed back.
     *
     * <p>A totem in either hand qualifies; the {@code infinity-keeps-totem} switch exists because
     * a totem is spent in death rather than in use, and an owner may want apples to last while
     * totems stay mortal.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack totem = poppingTotem(player, event.getHand());
        if (totem == null || !hasInfinity(totem)
                || !CombatRules.keepsTotem(combat.get(), totem.getType().getKey().getKey())) {
            return;
        }

        // Drain-then-append: the restore task removes whatever is waiting, so two pops in quick
        // succession are both remembered and neither is restored twice.
        totemsInPlay.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>()).add(totem.clone());
        UUID playerId = player.getUniqueId();
        scheduler.runSyncLater(() -> restoreTotems(playerId),
                Duration.ofMillis(TOTEM_RESTORE_DELAY_TICKS * 50L));
    }

    /** The delayed half of the totem restore, run on the tick thread. */
    private void restoreTotems(UUID playerId) {
        List<ItemStack> remembered = totemsInPlay.remove(playerId);
        if (remembered == null || remembered.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        // The player has respawned by now; the totems go wherever there is room, and the overflow
        // is dropped rather than lost - the same rule the catalogue's handout follows.
        for (ItemStack totem : remembered) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(totem);
            for (ItemStack remaining : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), remaining);
            }
        }
    }

    /** A disconnecting player's remembered totems are dropped rather than kept. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        totemsInPlay.remove(event.getPlayer().getUniqueId());
    }

    /** A dying player's remembered totems are dropped rather than kept. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        totemsInPlay.remove(event.getEntity().getUniqueId());
    }

    /**
     * The totem stack that is about to pop: the hand the event names, or - when the event carries
     * no hand at all - whichever hand is holding a totem.
     */
    private static ItemStack poppingTotem(Player player, EquipmentSlot hand) {
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return null;
        }
        if (hand == null) {
            ItemStack main = equipment.getItemInMainHand();
            return isTotem(main) ? main : equipment.getItemInOffHand();
        }
        return hand == EquipmentSlot.OFF_HAND ? equipment.getItemInOffHand() : equipment.getItemInMainHand();
    }

    private static boolean isTotem(ItemStack item) {
        return item != null && item.getType().getKey().getKey().equals("totem_of_undying");
    }

    private static boolean hasInfinity(ItemStack item) {
        return item.getEnchantmentLevel(Enchantment.INFINITY) > 0;
    }
}
