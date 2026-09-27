package net.abled.medieval.paper.cmdblock;

import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
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
 *       another player, so a stolen command block cannot be picked apart off-world either.</li>     *       <li>{@code BlockRedstoneEvent}: a marked block only accepts redstone while its owner
     *       is online. A signal arriving while they are away cannot be theirs, so it is refused;
     *       while they are on, their own buttons, levers and clocks all work.</li>
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
     * A marked command block only fires while its owner is online.
     *
     * <p>{@code BlockRedstoneEvent} has no player, so "the owner pressed this button" cannot be
     * verified directly - and refusing every signal made the owner's own buttons dead too, which
     * read in game as "the block does not work". The rule instead is presence: a marked block
     * fires for any redstone, but only while the owner who placed it is on the server. An owner
     * who is online is around to see what their contraptions do; a signal arriving while they are
     * away cannot be theirs, so it is refused.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getNewCurrent() <= 0 || !isGuarded(event.getBlock())) {
            return;
        }
        String owner = ownerOf(event.getBlock());
        if (owner == null || Bukkit.getPlayerExact(owner) == null) {
            // The stamp proves the block was owner-placed; if the owner is not online now, this
            // signal cannot be theirs. The interact listener still guards the GUI and breaking.
            event.setNewCurrent(0);
        }
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

    /** The stamped owner name of a command block, or null when it carries no mark. */
    private String ownerOf(Block block) {
        BlockState state = block.getState();
        if (!(state instanceof CommandBlock commandBlock)) {
            return null;
        }
        return commandBlock.getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
    }
}
