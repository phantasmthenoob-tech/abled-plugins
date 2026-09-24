package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.world.Dimension;
import net.abled.medieval.core.world.DimensionAccessService;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /nether open|close|status} and {@code /end open|close|status}.
 *
 * <p>One command per dimension rather than a single {@code /medieval dimension <name> ...}, because
 * a dimension gate is something staff reach for in a hurry and the short form is what they will
 * type. Both commands are built from the same code path, so they cannot drift apart.
 *
 * <h2>Threads</h2>
 * The state change is a database write, so it runs off the tick thread. Everything that touches the
 * server - the reply and the evacuation below - is handed back through
 * {@link MedievalScheduler#runSync}.
 *
 * <h2>Closing while players are inside</h2>
 * A closed gate blocks entry through portals, but anyone already in the dimension would be stranded
 * behind it, so closing evacuates them to the overworld spawn. An administrator who wants the
 * dimension empty gets exactly that; one who only wanted to stop new arrivals can reopen it and let
 * them walk back.
 */
public final class DimensionCommands {

    public static final String PERMISSION = "medieval.command.dimension";

    private final DimensionAccessService access;
    private final MedievalScheduler scheduler;
    private final MessageRenderer renderer;
    private final Logger logger;

    public DimensionCommands(DimensionAccessService access, MedievalScheduler scheduler,
                            MessageRenderer renderer, Logger logger) {
        this.access = Objects.requireNonNull(access, "access");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The top-level command node for one dimension, to be registered on its own. */
    public LiteralCommandNode<CommandSourceStack> node(Dimension dimension) {
        return Commands.literal(dimension.id())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("open")
                        .executes(context -> apply(context.getSource(), dimension, true)))
                .then(Commands.literal("close")
                        .executes(context -> apply(context.getSource(), dimension, false)))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource(), dimension)))
                .executes(context -> status(context.getSource(), dimension))
                .build();
    }

    private int apply(CommandSourceStack source, Dimension dimension, boolean open) {
        CommandSender sender = source.getSender();
        String actor = sender instanceof Player player ? player.getName() : "console";

        scheduler.runAsync(() -> {
            boolean changed;
            try {
                changed = access.setOpen(dimension, open, actor);
            } catch (RuntimeException failure) {
                logger.log(Level.SEVERE, "Could not change the " + dimension.id() + " gate", failure);
                scheduler.runSync(() -> renderer.send(sender, "storage-unavailable", true));
                return;
            }

            scheduler.runSync(() -> {
                renderer.send(sender, stateKey(open, changed), Map.of("dimension", dimension.displayName()), true);
                if (!changed) {
                    return;
                }
                logger.info(actor + (open ? " opened " : " closed ") + dimension.displayName());
                if (!open) {
                    evacuate(dimension);
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSourceStack source, Dimension dimension) {
        renderer.send(source.getSender(),
                access.isOpen(dimension) ? "dimension-status-open" : "dimension-status-closed",
                Map.of("dimension", dimension.displayName()), true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Moves everyone out of a dimension that was just closed.
     *
     * <p>Must run on the tick thread: it reads and moves live entities. If the overworld is missing,
     * which only happens on a server whose level-name is a non-overworld environment, players stay
     * where they are rather than being moved to a world that does not exist.
     */
    private void evacuate(Dimension dimension) {
        World.Environment environment = switch (dimension) {
            case NETHER -> World.Environment.NETHER;
            case END -> World.Environment.THE_END;
        };
        World destination = overworld();
        if (destination == null) {
            logger.warning("No overworld is loaded; leaving players inside " + dimension.displayName());
            return;
        }

        Location exit = destination.getSpawnLocation();
        int moved = 0;
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != environment) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                player.teleport(exit);
                renderer.send(player, "dimension-evacuated",
                        Map.of("dimension", dimension.displayName()), true);
                moved++;
            }
        }
        if (moved > 0) {
            logger.info("Evacuated " + moved + " player(s) from " + dimension.displayName());
        }
    }

    private static World overworld() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return null;
    }

    private static String stateKey(boolean open, boolean changed) {
        if (!changed) {
            return open ? "dimension-already-open" : "dimension-already-closed";
        }
        return open ? "dimension-opened" : "dimension-closed";
    }
}
