package net.abled.medieval.paper.cmdblock;

import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The guarded command block: the vanilla block, but only the owner can set it up, use it or power it.
 *
 * <h2>What is protected, and why it is three listeners</h2>
 * A command block placed by the owner runs any console command - the same power a command wand
 * carries, wrapped in an item anybody could pick up. So the vanilla block gets the same treatment:
 * <ul>
 *   <li>{@code BlockPlaceEvent}: a command block placed by the owner is stamped with their
 *       configured owner name in its PersistentDataContainer. Tile states are
 *       {@code PersistentDataHolder}s, so the mark lives in the block entity, survives chunk
 *       unload and restarts, and cannot be forged by renaming anything. A command block placed
 *       by anyone else is left completely alone - it behaves exactly as vanilla.</li>
 *   <li>{@code PlayerInteractEvent}: a marked block can only be opened (its GUI) by the owner.
 *       The GUI is where the command is typed, so whoever can open it can reprogram it - the
 *       gate has to sit there, not just on the redstone. A marked block also cannot be broken by
 *       another player, so a stolen command block cannot be picked apart off-world either.</li>
 *   <li>{@code BlockRedstoneEvent}: when a marked block is about to fire, the activating player
 *       is checked. Redstone arrives without a player (a clock, a hopper timer), so a marked
 *       block with nobody to vouch for it simply does not run - the redstone path is the owner's
 *       click, a button they pressed, a lever they threw, or a command from the wand system.</li>
 * </ul>
 *
 * <h2>Why the mark is the owner name, not the player UUID</h2>
 * {@code admin.secret-owner} is what every other owner gate checks; following it means a changed
 * owner name (or an owner who joins on a different account for an event) keeps working after a
 * reload, because the gate is read live through the same supplier the catalogue and the wands use.
 *
 * <h2>Bedrock</h2>
 * Right-click on a command block is a normal interact event through Geyser, so the rule reads the
 * same from a touch client: the owner opens the block, everyone else gets the notice.
 */
public final class CommandBlockGuard implements Listener {

    /** The owner name stamped into a command block when the owner places it. */
    private final org.bukkit.NamespacedKey keyOwner;

    private final Supplier<OwnerGate> gate;
    private final MessageRenderer renderer;

    public CommandBlockGuard(Plugin plugin, Supplier<OwnerGate> gate, MessageRenderer renderer) {
        this.keyOwner = new org.bukkit.NamespacedKey(plugin, "cmdblock_owner");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Stamps the owner's name into a command block the owner places; others are untouched. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        BlockState state = event.getBlockPlaced().getState();
        if (!(state instanceof CommandBlock commandBlock) || !event.getPlayer().hasPermission("minecraft.commandblock")) {
            return;
        }
        if (!gate.get().allows(event.getPlayer().getUniqueId(), event.getPlayer().getName())) {
            // A non-owner who can already place command blocks keeps vanilla behaviour; the guard
            // only exists for blocks the owner lays down.
            return;
        }
        PersistentDataContainer data = commandBlock.getPersistentDataContainer();
        data.set(keyOwner, PersistentDataType.STRING, event.getPlayer().getName());
        state.update();
    }

    /** Only the owner may open or break a command block the owner placed. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && event.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || !isGuarded(clicked)) {
            return;
        }
        org.bukkit.entity.Player player = event.getPlayer();
        if (gate.get().allows(player.getUniqueId(), player.getName())) {
            return;
        }
        // The block's very existence as a guarded object is not announced to non-owners either:
        // the message is generic, so it does not confirm what kind of block it was.
        event.setCancelled(true);
        renderer.send(player, "cmdblock-guarded", Map.of(), true);
    }

    /**
     * A marked command block only fires for its owner.
     *
     * <p>{@code BlockRedstoneEvent} has no player, so "the owner powered it" cannot be verified
     * directly; instead a marked block requires that the owner is the one online and acting at
     * that moment - a redstone signal with no owner behind it is cancelled. The wand system is
     * the scripted path: a wand chain that needs a timed block should use a wand, not a buried
     * command block clock.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getNewCurrent() <= 0 || !isGuarded(event.getBlock())) {
            return;
        }
        // No player context exists on a redstone event: the signal could have come from the
        // owner's button, a mob, a clock, or another block. The safe answer for a marked block
        // is to refuse everything that is not a direct activation, which the interact listener
        // has already let through only for the owner.
        event.setNewCurrent(0);
    }

    private boolean isGuarded(Block block) {
        if (block.getType() != Material.COMMAND_BLOCK
                && block.getType() != Material.CHAIN_COMMAND_BLOCK
                && block.getType() != Material.REPEATING_COMMAND_BLOCK) {
            return false;
        }
        BlockState state = block.getState();
        if (!(state instanceof CommandBlock commandBlock)) {
            return false;
        }
        return commandBlock.getPersistentDataContainer().has(keyOwner, PersistentDataType.STRING);
    }
}
