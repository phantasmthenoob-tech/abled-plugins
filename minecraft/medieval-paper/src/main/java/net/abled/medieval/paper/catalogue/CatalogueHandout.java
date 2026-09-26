package net.abled.medieval.paper.catalogue;

import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The one way items reach a player from either catalogue GUI.
 *
 * <p>{@link CatalogueListener} has always handed items out through one method: build a clean stack
 * from the item type, add it, and drop whatever did not fit at the player's feet with a message.
 * The enchantment picker gives out finished items too, and it must behave identically - a full
 * inventory cannot silently eat an enchanted blade any more than a plain one. So the logic lives
 * here rather than in the listener, and both GUIs call it.
 *
 * <p>What is given is built from scratch: the menu's decorative lore never travels with the item.
 */
public final class CatalogueHandout {

    private final MessageRenderer renderer;

    public CatalogueHandout(MessageRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /**
     * Puts the stack in the player's inventory, dropping the remainder at their feet if it does not
     * all fit.
     *
     * @return what was actually added - the asked amount when everything fit, less otherwise
     */
    public int give(Player player, ItemStack stack) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(stack, "stack");

        int asked = Math.max(1, stack.getAmount());
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (leftover.isEmpty()) {
            return asked;
        }

        int dropped = 0;
        for (ItemStack remaining : leftover.values()) {
            dropped += remaining.getAmount();
            player.getWorld().dropItem(player.getLocation(), remaining);
        }
        renderer.send(player, "catalogue-overflow",
                Map.of("item", Names.of(stack.getType()).toLowerCase(Locale.ROOT)), true);
        return asked - dropped;
    }
}
