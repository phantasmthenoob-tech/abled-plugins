package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.paper.catalogue.CatalogueIndex;
import net.abled.medieval.paper.catalogue.CatalogueMenu;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * {@code /medieval statuscheck}: the hidden owner catalogue.
 *
 * <h2>Why the name is not the protection</h2>
 * The command is gated by {@link OwnerGate}, not by its name. Brigadier's {@code requires} predicate
 * also keeps it out of command listings and tab completion for everyone else, but that is a
 * convenience, not the rule: the check runs on execution as well, and on every nested call, so
 * knowing the spelling gains nothing.
 *
 * <p>The gate is read through a supplier rather than captured once, so {@code /medieval reload}
 * applies a changed {@code admin.secret-owner} immediately. The cost is parsing a name or UUID per
 * invocation, which is nothing next to the inventory work the command does.
 *
 * <p>Deliberately not a permission: the point is one specific player, whether or not they are an
 * operator, and an operator check would let every other op in.
 */
public final class CatalogueCommand {

    public static final String LABEL = "statuscheck";

    private final CatalogueIndex index;
    private final MessageRenderer renderer;
    private final Supplier<OwnerGate> gate;
    private final Logger logger;

    public CatalogueCommand(CatalogueIndex index, MessageRenderer renderer, Supplier<OwnerGate> gate, Logger logger) {
        this.index = Objects.requireNonNull(index, "index");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The {@code statuscheck} branch, to be attached under {@code /medieval}. */
    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(LABEL)
                .requires(source -> player(source).isPresent() && isOwner(source.getSender()))
                .executes(context -> open(context.getSource()))
                .build();
    }

    /** True when this sender may use the catalogue; used by the help listing. */
    public boolean isOwner(CommandSender sender) {
        return sender instanceof Player player && gate.get().allows(player.getUniqueId(), player.getName());
    }

    private int open(CommandSourceStack source) {
        Optional<Player> owner = player(source);
        if (owner.isEmpty()) {
            // Unreachable through the requires predicate, kept so a future code path cannot open an
            // inventory on a sender that does not have one.
            return Command.SINGLE_SUCCESS;
        }

        Player player = owner.get();
        CatalogueMenu menu = new CatalogueMenu(index, renderer, player);
        menu.open(player);
        logger.info(player.getName() + " opened the owner catalogue (" + index.total()
                + " items, tab " + menu.category().id() + ")");
        return Command.SINGLE_SUCCESS;
    }

    private static Optional<Player> player(CommandSourceStack source) {
        return source.getSender() instanceof Player player ? Optional.of(player) : Optional.empty();
    }
}
