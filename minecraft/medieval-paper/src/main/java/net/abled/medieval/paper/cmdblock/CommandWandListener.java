package net.abled.medieval.paper.cmdblock;

import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
        // fishing rod, and right-clicking it must not also cast a bobber or place a block.
        event.setCancelled(true);
        service.use(id.get(), player);
    }
}
