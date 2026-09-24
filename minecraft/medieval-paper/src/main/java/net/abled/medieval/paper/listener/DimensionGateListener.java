package net.abled.medieval.paper.listener;

import net.abled.medieval.core.world.Dimension;
import net.abled.medieval.core.world.DimensionAccessService;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Enforces the dimension gates on portal travel.
 *
 * <p>Without this, {@code dimensions.nether.enabled: false} would be a comment in a configuration
 * file: the portal would still work. This is the listener that makes the setting real.
 *
 * <h2>What is cancelled and why</h2>
 * Portal travel fires {@link PlayerPortalEvent} for players and {@link EntityPortalEvent} for
 * everything else. Both are cancelled for a closed destination, so a closed dimension cannot be
 * entered by walking in, by riding a mob through, by naming it on a mount, or by pushing a chest
 * full of loot through a portal. The destination world from the event is used rather than a
 * hardcoded nether/end assumption, so the gate follows whatever dimension a portal actually leads
 * to and keeps working if another plugin redirects a portal elsewhere.
 *
 * <p>Building a portal is deliberately still allowed: the frame lights, and standing in it does
 * nothing. That is a clearer signal than silently refusing the flint and steel, and it means a
 * dimension an event opens later already has its portals in place.
 *
 * <h2>Priority</h2>
 * {@code HIGHEST} so the decision is made after other plugins had the chance to redirect a portal.
 */
public final class DimensionGateListener implements Listener {

    private final DimensionAccessService access;
    private final MessageRenderer renderer;

    public DimensionGateListener(DimensionAccessService access, MessageRenderer renderer) {
        this.access = Objects.requireNonNull(access, "access");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Optional<Dimension> destination = destinationOf(event.getTo());
        if (destination.isEmpty() || access.isOpen(destination.get())) {
            return;
        }

        event.setCancelled(true);
        renderer.send(event.getPlayer(), "dimension-entry-denied",
                Map.of("dimension", destination.get().displayName()), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Optional<Dimension> destination = destinationOf(event.getTo());
        if (destination.isEmpty() || access.isOpen(destination.get())) {
            return;
        }

        event.setCancelled(true);

        Entity entity = event.getEntity();
        if (entity instanceof Player player) {
            // Players normally arrive through PlayerPortalEvent; this keeps the message correct for
            // any path that reports the entity event instead.
            renderer.send(player, "dimension-entry-denied",
                    Map.of("dimension", destination.get().displayName()), true);
        }
    }

    /**
     * Maps a portal destination onto a gated dimension.
     *
     * <p>The environment is what identifies a dimension, not the world name: a server may rename
     * its levels, and a portal leading to the overworld is never gated.
     */
    private static Optional<Dimension> destinationOf(org.bukkit.Location destination) {
        if (destination == null || destination.getWorld() == null) {
            return Optional.empty();
        }
        World.Environment environment = destination.getWorld().getEnvironment();
        if (environment == World.Environment.NETHER) {
            return Optional.of(Dimension.NETHER);
        }
        if (environment == World.Environment.THE_END) {
            return Optional.of(Dimension.END);
        }
        return Optional.empty();
    }
}
