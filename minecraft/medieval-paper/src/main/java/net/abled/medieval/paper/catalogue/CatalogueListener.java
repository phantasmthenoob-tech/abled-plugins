package net.abled.medieval.paper.catalogue;

import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Drives {@link CatalogueMenu}: recognises clicks in the catalogue's own inventory and hands out
 * items.
 *
 * <h2>Nothing moves by default</h2>
 * Every click in a catalogue view is cancelled first and then interpreted. That is deliberate:
 * the menu's items are buttons, so vanilla behaviour (picking an item up onto the cursor, shift-
 * transferring it into the player's inventory, dropping it with Q, hotbar-swapping it) must not
 * happen. Items reach a player only through {@link #take}, which decides the amount itself - so a
 * click can never yield more than the rule allows.
 *
 * <h2>Threads</h2>
 * Inventory events are delivered on the tick thread, and everything here - rendering, giving items,
 * dropping leftovers - touches live server state, so none of it is moved off that thread.
 */
public final class CatalogueListener implements Listener {

    private final MessageRenderer renderer;

    public CatalogueListener(MessageRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        CatalogueMenu menu = menuOf(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }

        // Cancel before deciding anything: nothing about a catalogue click is vanilla behaviour.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // A click in the player's own inventory (or outside the window, where the inventory is
        // null) is not a catalogue action.
        if (event.getClickedInventory() != menu.getInventory()) {
            return;
        }

        int slot = event.getRawSlot();
        if (menu.selectTab(slot)) {
            return;
        }
        if (menu.navigate(slot, player)) {
            return;
        }

        menu.content(slot).ifPresent(material -> take(player, material, menu.amountFor(event.getClick(), material)));
    }

    /** A drag across the menu would otherwise scatter items the menu does not own. */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    private void take(Player player, Material material, int amount) {
        if (amount <= 0) {
            return;
        }

        int stackSize = Math.max(1, Math.min(amount, material.getMaxStackSize()));
        // addItem returns whatever did not fit instead of discarding it, so a full inventory drops
        // the remainder at the player's feet rather than silently eating the items.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, stackSize));
        if (leftover.isEmpty()) {
            return;
        }

        for (ItemStack remaining : leftover.values()) {
            player.getWorld().dropItem(player.getLocation(), remaining);
        }
        renderer.send(player, "catalogue-overflow",
                Map.of("item", material.name().toLowerCase(Locale.ROOT)), true);
    }

    private static CatalogueMenu menuOf(Inventory inventory) {
        InventoryHolder holder = inventory == null ? null : inventory.getHolder();
        return holder instanceof CatalogueMenu menu ? menu : null;
    }
}
