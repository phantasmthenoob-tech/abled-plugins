package net.abled.loginguard;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Blocks everything a not-yet-authenticated player can do, and prompts them on join.
 */
final class LoginListener implements Listener {

    private final LoginManager manager;
    private final Logger logger;
    private final boolean debug;

    LoginListener(LoginManager manager, Logger logger, boolean debug) {
        this.manager = manager;
        this.logger = logger;
        this.debug = debug;
    }

    private boolean locked(UUID id) {
        return !manager.isAuthenticated(id);
    }

    private void dbg(String format, Object... args) {
        if (debug) {
            logger.info("[debug] " + String.format(format, args));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
        dbg("join %s locked=%b", event.getPlayer().getName(), locked(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dbg("quit %s", event.getPlayer().getName());
        manager.handleQuit(event.getPlayer().getUniqueId());
    }

    /**
     * Corrects a locked player's drift within the same movement event: setTo rewrites the packet
     * in place, no teleport is issued, so the client's teleport-confirmation handshake is never
     * touched and Paper's "Invalid move player packet received" kick cannot be provoked from
     * here. Position delta only - rotation stays, or even standing still would fire.
     * <p>Debug logging is deliberately NOT in the common path: one line format is only used once
     * the correction is real, meaning a locked player's normal idling never floods the log.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!locked(event.getPlayer().getUniqueId())) {
            return;
        }
        var from = event.getFrom();
        var to = event.getTo();
        if (to == null) {
            return;
        }
        if (Math.abs(from.getX() - to.getX()) > 1e-4
                || Math.abs(from.getZ() - to.getZ()) > 1e-4
                || Math.abs(from.getY() - to.getY()) > 1e-4) {
            dbg("move corrected for %s: %s -> %s",
                    event.getPlayer().getName(), from.toVector(), to.toVector());
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String root = event.getMessage().split(" ")[0].toLowerCase().substring(1);
        boolean exempt = root.equals("register") || root.equals("reg") || root.equals("login") || root.equals("l");
        dbg("command %s for %s, locked=%b, exempt=%b",
                root, event.getPlayer().getName(), locked(event.getPlayer().getUniqueId()), exempt);
        if (locked(event.getPlayer().getUniqueId()) && !exempt) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (locked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (locked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (locked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof org.bukkit.entity.Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (locked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        dbg("teleport %s cause=%s locked=%b", event.getPlayer().getName(), event.getCause(), locked(event.getPlayer().getUniqueId()));
        if (locked(event.getPlayer().getUniqueId())
                && (event.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND
                    || event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
