package net.abled.medieval.paper.withers;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * The summon saddlebag: right-click a summoned wither or wither skeleton while holding the
 * Wither's Bane sword, and a chest GUI opens with that creature's storage AND its equipment.
 *
 * <h2>Layout</h2>
 * The top row is the creature's live equipment: helmet, chestplate, leggings, boots, weapon
 * (main hand), and off-hand, in that order. Everything below is the creature's own storage.
 * Equipment slots are live - what you put in the helmet slot is what the skeleton is wearing
 * the moment you close the menu, and what it was wearing sits in the slot when you open it.
 *
 * <h2>How the storage works</h2>
 * Neither entity type has a native inventory, so the bag items live in the creature's own
 * PersistentDataContainer, serialised with Paper's {@code serializeItemsAsBytes} - the same
 * encoding vanilla uses for bundles, so NBT, enchantments and durability all round-trip. Because
 * the data rides on the entity, it survives chunk unload and restarts with no extra table.
 *
 * <h2>How the GUI works</h2>
 * The chest inventory is backed by a small holder that remembers which creature it edits. Every
 * close writes the bag grid back to the PDC and swaps the equipment onto the creature, so there
 * is no save button to forget.
 */
public final class SummonStorageListener implements Listener {

    /** Storage slots per summon: withers get a bigger saddlebag than skeletons. */
    private static final int SKELETON_BAG_SLOTS = 15;
    private static final int WITHER_BAG_SLOTS = 27;

    /** Equipment row is always six slots: helmet, chest, legs, boots, weapon, off-hand. */
    private static final int EQUIPMENT_SLOTS = 6;

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
        boolean isWither = clicked instanceof Wither;
        int bagSlots = isWither ? WITHER_BAG_SLOTS : SKELETON_BAG_SLOTS;
        String title = isWither ? "Wither Saddlebag" : "Wither Skeleton Bag";

        Inventory inventory = Bukkit.createInventory(
                new SummonBagHolder(clicked.getUniqueId(), bagSlots),
                EQUIPMENT_SLOTS + bagSlots,
                net.kyori.adventure.text.Component.text(title));

        // Top row: the creature's live equipment.
        EntityEquipment equipment = clicked instanceof LivingEntity living
                ? living.getEquipment() : null;
        if (equipment != null) {
            inventory.setItem(0, equipment.getHelmet());
            inventory.setItem(1, equipment.getChestplate());
            inventory.setItem(2, equipment.getLeggings());
            inventory.setItem(3, equipment.getBoots());
            inventory.setItem(4, equipment.getItemInMainHand());
            inventory.setItem(5, equipment.getItemInOffHand());
        }

        // Below: the bag.
        ItemStack[] bag = load(clicked, bagSlots);
        for (int index = 0; index < bagSlots; index++) {
            inventory.setItem(EQUIPMENT_SLOTS + index, bag[index]);
        }
        player.openInventory(inventory);
    }

    /**
     * Guards the equipment row while the menu is open: those six slots hold items that belong on
     * the creature, and a click that would pull them out is allowed (the player may take the
     * armour off), but nothing may be placed there except wearable/held items - anything else
     * would swap onto the creature and fall off into the void on close.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SummonBagHolder holder)) {
            return;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot >= EQUIPMENT_SLOTS) {
            return; // The bag row behaves like any chest.
        }
        // Any click that would PUT something into the equipment row must be wearable. Taking out
        // is always fine. The cursor item is what would land there on a place/swap click.
        ItemStack wouldPlace = event.getCursor();
        if (wouldPlace == null || wouldPlace.getType().isAir()) {
            return;
        }
        boolean acceptable = switch (slot) {
            case 0 -> wouldPlace.getType().name().endsWith("_HELMET");
            case 1 -> wouldPlace.getType().name().endsWith("_CHESTPLATE");
            case 2 -> wouldPlace.getType().name().endsWith("_LEGGINGS");
            case 3 -> wouldPlace.getType().name().endsWith("_BOOTS");
            case 4, 5 -> true; // Any held item is fine in either hand.
            default -> false;
        };
        if (!acceptable) {
            event.setCancelled(true);
        }
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



    /**
     * Every close persists: the bag grid goes back to the PDC, and the top row is swapped onto
     * the creature as its live equipment. The old equipment is not "saved" anywhere - whatever
     * the player left in the equipment row is what the creature wears; taking an item out and
     * closing means it is in the player's cursor or wherever they put it.
     */
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

        // Equipment row first, swapped onto the creature.
        if (summon instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                equipment.setHelmet(event.getInventory().getItem(0));
                equipment.setChestplate(event.getInventory().getItem(1));
                equipment.setLeggings(event.getInventory().getItem(2));
                equipment.setBoots(event.getInventory().getItem(3));
                equipment.setItemInMainHand(event.getInventory().getItem(4));
                equipment.setItemInOffHand(event.getInventory().getItem(5));
            }
        }

        // Then the bag below the equipment row.
        int bagSlots = holder.bagSlots();
        ItemStack[] bag = new ItemStack[bagSlots];
        for (int index = 0; index < bagSlots; index++) {
            bag[index] = event.getInventory().getItem(EQUIPMENT_SLOTS + index);
        }
        // The stored bytes are purely the bag - the equipment row lives on the entity's real
        // equipment slots, not in storage.
        summon.getPersistentDataContainer().set(keyStorage, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(bag));
    }

    /** Marks a chest GUI as a summon's bag and remembers which creature it edits. */
    record SummonBagHolder(UUID summonId, int bagSlots) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null; // Holder-only; the real inventory is created around it.
        }
    }
}
