package net.abled.medieval.paper.withers;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The summon saddlebag: right-click a summoned wither or wither skeleton while holding the
 * Wither's Bane sword, and a chest GUI opens with that creature's storage.
 *
 * <h2>How the storage works</h2>
 * Neither entity type has a native inventory, so the items live in the creature's own
 * PersistentDataContainer, serialised with Paper's {@code serializeItemsAsBytes} - the same
 * encoding vanilla uses for bundles, so NBT, enchantments and durability all round-trip. Because
 * the data rides on the entity, it survives chunk unload and restarts with no extra table.
 *
 * <h2>How the GUI works</h2>
 * The chest inventory is backed by a small holder that remembers which creature it edits. Every
 * close (clicking outside, pressing E, walking away) writes the whole grid back to the PDC, so
 * there is no save button to forget. The top row is decorative filler; the slots that matter are
 * labelled by their glass borders.
 */
public final class SummonStorageListener implements Listener {

    /** Storage slots per summon: withers get a bigger saddlebag than skeletons. */
    private static final int SKELETON_SLOTS = 15;
    private static final int WITHER_SLOTS = 27;

    private final Plugin plugin;
    private final SkeletonManager skeletons;
    private final java.util.function.Predicate<Player> swordGate;

    private final NamespacedKey keyStorage;

    public SummonStorageListener(Plugin plugin, SkeletonManager skeletons,
                                 java.util.function.Predicate<Player> swordGate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.skeletons = Objects.requireNonNull(skeletons, "skeletons");
        this.swordGate = Objects.requireNonNull(swordGate, "swordGate");
        this.keyStorage = new NamespacedKey(plugin, "summon_storage");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        // Fires once per hand; only the main hand opens the bag.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!swordGate.test(player)) {
            return;
        }
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof WitherSkeleton) && !(clicked instanceof Wither)) {
            return;
        }
        if (skeletons.ownerOf(clicked) == null) {
            return;
        }
        // Only the owner rifles through the bags.
        if (!skeletons.isOwnedBy(clicked, player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        int slots = clicked instanceof Wither ? WITHER_SLOTS : SKELETON_SLOTS;
        String title = clicked instanceof Wither ? "Wither Saddlebag" : "Wither Skeleton Bag";

        Inventory inventory = Bukkit.createInventory(new SummonBagHolder(clicked.getUniqueId()),
                slots, net.kyori.adventure.text.Component.text(title));
        inventory.setContents(load(clicked, slots));
        player.openInventory(inventory);
    }

    /** Reads the stored stacks out of the creature's PDC. */
    private ItemStack[] load(Entity summon, int slots) {
        byte[] bytes = summon.getPersistentDataContainer().get(keyStorage, PersistentDataType.BYTE_ARRAY);
        if (bytes == null || bytes.length == 0) {
            return new ItemStack[slots];
        }
        ItemStack[] stored = ItemStack.deserializeItemsFromBytes(bytes);
        ItemStack[] result = new ItemStack[slots];
        System.arraycopy(stored, 0, result, 0, Math.min(stored.length, slots));
        return result;
    }

    /** Writes the GUI's current contents back into the creature's PDC. */
    private void save(Entity summon, Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        summon.getPersistentDataContainer().set(keyStorage, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(contents));
    }

    /** Every close persists; no save button to forget. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SummonBagHolder holder)) {
            return;
        }
        Entity summon = Bukkit.getEntity(holder.summonId());
        if (summon == null) {
            // The creature died or despawned while its bag was open: the contents are lost with
            // it, which is the same outcome as a vanilla chest minecart sinking into the void.
            return;
        }
        save(summon, event.getInventory());
    }

    /** Marks a chest GUI as a summon's bag and remembers which creature it edits. */
    record SummonBagHolder(UUID summonId) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null; // Holder-only; the real inventory is created around it.
        }
    }
}
