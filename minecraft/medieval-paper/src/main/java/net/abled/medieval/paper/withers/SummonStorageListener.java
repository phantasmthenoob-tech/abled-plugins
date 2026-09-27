package net.abled.medieval.paper.withers;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
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
 * <h2>Why two interact events</h2>
 * A right-click on a living entity arrives as both {@code PlayerInteractAtEntityEvent} and
 * {@code PlayerInteractEntityEvent} on most clients, and on some only one of the two fires.
 * Both are handled; opening the GUI twice within a tick is harmless because the second open
 * simply replaces the first view of the same contents.
 */
public final class SummonStorageListener implements Listener {

    // Storage slots per summon. Chest inventories must be a multiple of nine slots, so the total
    // is the equipment row (9, padded with three inert slots after the six real ones) plus whole
    // rows of bag: two rows for a skeleton, three for a wither. 21 and 33 - the honest sums -
    // throw at createInventory, which is why the GUI silently never opened.
    private static final int SKELETON_BAG_SLOTS = 18;
    private static final int WITHER_BAG_SLOTS = 27;

    /** The three padding slots after the six equipment slots; visually inert, clicks ignored. */
    private static final int PAD_SLOTS = 3;

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
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            openBag(event.getPlayer(), event.getRightClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            openBag(event.getPlayer(), event.getRightClicked());
        }
    }

    /** Opens the saddlebag if every gate passes; harmless if called twice in one tick. */
    private void openBag(Player player, Entity clicked) {
        if (!swordGate.test(player)) {
            return;
        }
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

        boolean isWither = clicked instanceof Wither;
        int bagSlots = isWither ? WITHER_BAG_SLOTS : SKELETON_BAG_SLOTS;
        String title = isWither ? "Wither Saddlebag" : "Wither Skeleton Bag";

        Inventory inventory = Bukkit.createInventory(
                new SummonBagHolder(clicked.getUniqueId(), bagSlots),
                EQUIPMENT_SLOTS + PAD_SLOTS + bagSlots,
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
     * Guards the equipment row while the menu is open: only wearable/held items may be put in
     * the six equipment slots, or closing the menu would try to make the creature wear a steak.
     * Taking items out is always allowed.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SummonBagHolder holder)) {
            return;
        }
        int slot = event.getSlot();
        if (slot >= EQUIPMENT_SLOTS && slot < EQUIPMENT_SLOTS + PAD_SLOTS) {
            event.setCancelled(true); // The padding after the equipment row holds nothing, ever.
            return;
        }
        if (slot < 0 || slot >= EQUIPMENT_SLOTS) {
            return; // The bag rows behave like any chest.
        }
        // The cursor item is what would land there on a place/swap click.
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
     * the creature as its live equipment. Whatever the player left in the equipment row is what
     * the creature wears; taking an item out and closing means it left with the player.
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

        // Then the bag below the equipment row. The stored bytes are purely the bag - the
        // equipment row lives on the entity's real equipment slots, not in storage.
        int bagSlots = holder.bagSlots();
        ItemStack[] bag = new ItemStack[bagSlots];
        for (int index = 0; index < bagSlots; index++) {
            bag[index] = event.getInventory().getItem(EQUIPMENT_SLOTS + index);
        }
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
