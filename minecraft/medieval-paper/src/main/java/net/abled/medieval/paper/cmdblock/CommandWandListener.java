package net.abled.medieval.paper.cmdblock;

import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fires a command wand when its owner right-clicks it.
 *
 * <h2>Who may fire a wand</h2>
 * Only the configured owner. The gate is re-read through a supplier so {@code /medieval reload}
 * applies a changed {@code admin.secret-owner} immediately, exactly as the catalogue command does.
 * A wand that falls into another player's hands is inert: the check runs before anything else, and
 * a non-owner's click on a wand behaves exactly like a click on an ordinary rod - nothing happens,
 * no message, no dispatch. This is deliberate: announcing "this is a command wand" to whoever
 * picks it up would advertise exactly the item that runs console commands.
 *
 * <h2>Which hand</h2>
 * {@code PlayerInteractEvent} fires once per hand. The wand is only honoured in the main hand, so
 * an off-hand rod cannot double-fire an impulse command, and a wand in the off hand while the main
 * hand holds a rod is not accidentally triggered.
 *
 * <h2>Bedrock</h2>
 * Right-click arrives as a normal interact event through Geyser, so the wand works the same from a
 * touch client: tap and hold to use. No click-type trickery is involved, following the GUI rule
 * the rest of the plugin uses.
 */
public final class CommandWandListener implements Listener {

    private final CommandWandService service;
    private final CommandWandFactory factory;
    private final Supplier<OwnerGate> gate;
    private final MessageRenderer renderer;

    public CommandWandListener(CommandWandService service, CommandWandFactory factory,
                               Supplier<OwnerGate> gate, MessageRenderer renderer) {
        this.service = Objects.requireNonNull(service, "service");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // Main hand only, so one right-click is one event for the wand, not two.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (!gate.get().allows(player.getUniqueId(), player.getName())) {
            return;
        }

        ItemStack held = event.getItem();
        Optional<UUID> id = factory.idOf(held);
        if (id.isEmpty()) {
            return;
        }

        // The wand's use wins over whatever the item would normally do: a command wand is not a
        // fishing rod. Denying the item use outright (not just cancelling) tells the server the
        // rod's action never happened, and removing any client-predicted cooldown makes the
        // player's very next click land immediately - without this, the client locks the item as
        // though a bobber were out and a wand clicked in quick succession feels laggy or dead.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (player.hasCooldown(Material.FISHING_ROD)) {
            player.setCooldown(Material.FISHING_ROD, 0);
        }

        service.use(id.get(), player);
    }

    /**
     * Eats the bobber a client-side rod cast may have produced.
     *
     * <p>The interact listener runs before the cast on the server, but a client that predicted
     * the cast can still push a fishing hook into the world before the cancellation round-trips.
     * Whatever hook still appears for a wand is removed, so the world stays clean and no wand
     * ever leaves a bobber dangling out of a wall or floor.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFish(PlayerFishEvent event) {
        if (!gate.get().allows(event.getPlayer().getUniqueId(), event.getPlayer().getName())) {
            return;
        }
        ItemStack rod = event.getPlayer().getInventory().getItem(event.getHand() == EquipmentSlot.OFF_HAND
                ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
        if (factory.idOf(rod).isPresent()) {
            event.setCancelled(true);
            event.getHook().remove();
        }
    }
}
