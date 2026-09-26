package net.abled.medieval.paper.catalogue;

import net.abled.medieval.paper.message.MessageRenderer;
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
import java.util.logging.Logger;

/**
 * Drives both catalogue GUIs: recognises clicks in a {@link CatalogueMenu} or an
 * {@link EnchantPicker} and carries out what they mean.
 *
 * <h2>Nothing moves by default</h2>
 * Every click in either view is cancelled first and then interpreted. That is deliberate: the
 * GUIs' items are buttons, so vanilla behaviour (picking an item up onto the cursor, shift-
 * transferring it into the player's inventory, dropping it with Q, hotbar-swapping it) must not
 * happen. Items reach a player only through {@link CatalogueHandout}, which decides the amount
 * itself - so a click can never yield more than the rule allows.
 *
 * <h2>The two armed modes</h2>
 * The main menu has two buttons that change what the <em>next item click</em> does. Enchant mode
 * opens the enchantment picker for the clicked item. Custom-amount mode asks for the amount in
 * chat and hands that many out immediately, so the click is answered rather than merely remembered.
 * Both disarm themselves on the click that uses them, and any other navigation click (a tab, a
 * page turn) also disarms custom-amount mode, so an armed state can never outlive the context it
 * was armed in. This is deliberately mode-then-click rather than click-then-menu because Geyser
 * cannot tell a right-click from a left-click: the mode has to live in a distinct slot.
 *
 * <h2>Threads</h2>
 * Inventory events are delivered on the tick thread, and everything here - rendering, giving items,
 * opening prompts - touches live server state, so none of it is moved off that thread.
 */
public final class CatalogueListener implements Listener {

    private final MessageRenderer renderer;
    private final CatalogueSearch search;
    private final CatalogueHandout handout;
    private final CatalogueIndex index;
    private final Logger logger;

    public CatalogueListener(MessageRenderer renderer, CatalogueSearch search, CatalogueHandout handout,
                             CatalogueIndex index, Logger logger) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.search = Objects.requireNonNull(search, "search");
        this.handout = Objects.requireNonNull(handout, "handout");
        this.index = Objects.requireNonNull(index, "index");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        EnchantPicker picker = pickerOf(event.getView().getTopInventory());
        if (picker != null) {
            // Cancel before deciding anything, exactly as in the main menu.
            event.setCancelled(true);
            if (event.getClickedInventory() == picker.getInventory()
                    && event.getWhoClicked() instanceof Player player) {
                pickEnchant(picker, player, event.getRawSlot());
            }
            return;
        }

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
            menu.setEnchantArmed(false);
            menu.setCustomAmountArmed(false);
            menu.clearCustomAmount();
            return;
        }
        // The search row. Asking for a query is the one button that closes the menu: the answer is
        // typed into chat, because the sign editor and anvil renaming a Java player could use are
        // exactly the screens Geyser does not carry to Bedrock.
        if (slot == CatalogueMenu.SLOT_SEARCH) {
            search.prompt(player, menu);
            return;
        }
        if (slot == CatalogueMenu.SLOT_CUSTOM_AMOUNT) {
            if (menu.hasCustomAmount()) {
                // The button shows the amount in force; clicking it drops it and returns to the
                // cycled preset, which is what its label says it does.
                menu.clearCustomAmount();
                renderer.send(player, "catalogue-custom-amount-cleared", true);
            } else {
                // Same armed-mode shape as enchant: arm, then the item click carries the context
                // (which item), because the button itself cannot know one.
                boolean nowArmed = !menu.isCustomAmountArmed();
                menu.setCustomAmountArmed(nowArmed);
                if (nowArmed) {
                    renderer.send(player, "catalogue-custom-amount-armed", true);
                }
            }
            return;
        }
        if (slot == CatalogueMenu.SLOT_ENCHANT) {
            menu.setEnchantArmed(!menu.isEnchantArmed());
            if (menu.isEnchantArmed()) {
                renderer.send(player, "catalogue-enchant-armed", true);
            }
            return;
        }
        if (slot == CatalogueMenu.SLOT_CLEAR_SEARCH) {
            // False while the button is the dimmed "nothing to clear" cell, which is inert by design.
            if (menu.clearSearch()) {
                renderer.send(player, "catalogue-search-cleared", true);
            }
            return;
        }
        if (menu.navigate(slot, player)) {
            menu.setEnchantArmed(false);
            return;
        }

        menu.content(slot).ifPresent(material ->
                takeClicked(player, menu, material, menu.amountFor(event.getClick(), material)));
    }

    /**
     * One clicked entry in the main menu, with the mode it lands in already resolved.
     *
     * <p>Enchant mode wins over taking: it is armed, the click is the item it was armed for, and
     * the click is consumed. A click with no take amount behind it (the inert click types) still
     * disarms, so an armed state cannot survive a click it did not answer.
     */
    private void takeClicked(Player player, CatalogueMenu menu, org.bukkit.Material material, int amount) {
        boolean wasEnchant = menu.isEnchantArmed();
        boolean wasAmount = menu.isCustomAmountArmed();
        menu.setEnchantArmed(false);
        menu.setCustomAmountArmed(false);
        if (wasEnchant) {
            openPicker(player, menu, material);
            return;
        }
        if (wasAmount) {
            // The armed click is answered in chat, which closes the menu - so the amount is asked
            // here rather than taken now.
            search.promptAmount(player, menu, material);
            return;
        }

        if (amount <= 0) {
            return;
        }

        int stackSize = Math.max(1, Math.min(amount, material.getMaxStackSize()));
        int given = handout.give(player, new ItemStack(material, stackSize));
        if (given > 0) {
            logger.info(player.getName() + " took " + given + " x "
                    + Names.id(material) + " from the catalogue");
        }
    }

    /** Arms the enchantment picker for the clicked item and opens it. */
    private void openPicker(Player player, CatalogueMenu menu, org.bukkit.Material material) {
        // The picker's startFrom is the clicked item; the draft starts empty.
        new EnchantPicker(index, renderer, handout).open(player, EnchantDraft.of(material), Names.of(material));
        logger.info(player.getName() + " opened the enchantment picker for " + Names.id(material));
    }

    /** One click in the enchantment picker. */
    private void pickEnchant(EnchantPicker picker, Player player, int slot) {
        if (slot == EnchantPicker.SLOT_DONE) {
            picker.finish(player);
            renderer.send(player, "enchant-finished", Map.of(
                    "item", Names.of(picker.draft().material())), true);
            player.closeInventory();
            logger.info(player.getName() + " took " + Names.id(picker.draft().material())
                    + " (" + picker.draft().enchantments().size() + " enchantment(s)) from the catalogue");
            return;
        }
        if (slot == EnchantPicker.SLOT_BACK) {
            // The menu the picker came from is not reachable from here; a fresh one on the current
            // tab is the same thing in practice, and the search state was per-menu anyway.
            new CatalogueMenu(index, renderer).open(player, false);
            return;
        }
        if (slot == EnchantPicker.SLOT_CLEAR) {
            picker.clear();
            renderer.send(player, "enchant-cleared", true);
            return;
        }
        if (slot == EnchantPicker.SLOT_PREVIOUS) {
            picker.shift(-1);
            return;
        }
        if (slot == EnchantPicker.SLOT_NEXT) {
            picker.shift(1);
            return;
        }

        picker.content(slot).ifPresent(enchantment -> search.promptLevel(player, picker, enchantment));
    }

    /** A drag across either menu would otherwise scatter items the menus do not own. */
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (menuOf(event.getView().getTopInventory()) != null
                || pickerOf(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    private static CatalogueMenu menuOf(Inventory inventory) {
        InventoryHolder holder = inventory == null ? null : inventory.getHolder();
        return holder instanceof CatalogueMenu menu ? menu : null;
    }

    private static EnchantPicker pickerOf(Inventory inventory) {
        InventoryHolder holder = inventory == null ? null : inventory.getHolder();
        return holder instanceof EnchantPicker picker ? picker : null;
    }
}
