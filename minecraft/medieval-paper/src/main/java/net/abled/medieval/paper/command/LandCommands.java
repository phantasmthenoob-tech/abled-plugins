package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.abled.medieval.paper.land.BlockSearchService;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * {@code /land search|status|cancel}: the player-facing closest-block search.
 *
 * <h2>Why it is its own command</h2>
 * {@code /land} is the land-facing namespace, and unlike the rest of the command surface this is not an
 * administrative tool - a player looking for their nearest diamond is exactly the intended user - so it
 * is registered at the top level where it is short enough to type and easy to find. Whether it works is
 * a configuration decision ({@code land.search.enabled}) and a permission ({@value #PERMISSION}), both
 * re-read per invocation, so {@code /medieval reload} takes effect immediately and the command vanishes
 * from tab completion when it is switched off.
 *
 * <p>The command itself does no searching. It validates the block, then hands over to
 * {@link BlockSearchService}, which walks the world across ticks; the player is told the answer in chat
 * when it arrives, which is what makes a search that takes minutes usable at all.
 */
public final class LandCommands {

    public static final String PERMISSION = "medieval.command.land";
    public static final String LABEL = "land";

    private static final String ARG_BLOCK = "block";
    private static final String ARG_RADIUS = "radius";

    /** Every block a player could reasonably ask for, built once for tab completion. */
    private List<String> cachedBlockNames;

    private final BlockSearchService search;
    private final MessageRenderer renderer;
    private final Supplier<Boolean> enabled;

    public LandCommands(BlockSearchService search, MessageRenderer renderer, Supplier<Boolean> enabled) {
        this.search = Objects.requireNonNull(search, "search");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    /** The top-level {@code /land} command, to be registered on its own. */
    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(LABEL)
                .requires(source -> enabled.get()
                        && source.getSender() instanceof Player
                        && source.getSender().hasPermission(PERMISSION))
                .executes(context -> usage(context.getSource()))
                .then(Commands.literal("search")
                        // Bare /land search reports progress, so the answer to "how is it going?" is one
                        // word away while a search is walking.
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument(ARG_BLOCK, StringArgumentType.word())
                                .suggests(blockNames())
                                .executes(context -> start(context.getSource(),
                                        argument(context, ARG_BLOCK), OptionalInt.empty()))
                                .then(Commands.argument(ARG_RADIUS, IntegerArgumentType.integer(1))
                                        .executes(context -> start(context.getSource(),
                                                argument(context, ARG_BLOCK),
                                                OptionalInt.of(context.getArgument(ARG_RADIUS, Integer.class)))))))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("cancel")
                        .executes(context -> cancel(context.getSource())))
                .build();
    }

    private int usage(CommandSourceStack source) {
        renderer.send(source.getSender(), "land-help", true);
        return Command.SINGLE_SUCCESS;
    }

    private int start(CommandSourceStack source, String blockName, OptionalInt radius) {
        CommandSender sender = source.getSender();
        Optional<Player> player = player(source);
        if (player.isEmpty()) {
            // Unreachable through the requires predicate; kept so no future code path can start a
            // search that has no player to report its answer to.
            return Command.SINGLE_SUCCESS;
        }

        Material block = Material.matchMaterial(blockName);
        if (block == null || !block.isBlock() || block.isAir()) {
            // Air is rejected along with nonsense: every position is air somewhere above the ground, so
            // "the closest air" would answer instantly and mean nothing.
            renderer.send(sender, "land-search-invalid-block", Map.of("input", blockName), true);
            return Command.SINGLE_SUCCESS;
        }

        search.start(player.get(), block, radius);
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSourceStack source) {
        Optional<Player> player = player(source);
        player.ifPresent(search::status);
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandSourceStack source) {
        Optional<Player> player = player(source);
        player.ifPresent(search::cancel);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Block names for tab completion, in the form {@code Material#matchMaterial} accepts: the resource
     * path of the block's key, so {@code diamond_ore} and not {@code DIAMOND_ORE}.
     */
    private SuggestionProvider<CommandSourceStack> blockNames() {
        return (context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String name : searchableBlocks()) {
                if (name.startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    private List<String> searchableBlocks() {
        if (cachedBlockNames == null) {
            // Built from the server's own registry rather than a list of favourites, so every block this
            // version has is searchable, including the ones a new update adds.
            cachedBlockNames = Arrays.stream(Material.values())
                    .filter(material -> material.isBlock() && !material.isAir())
                    .map(material -> material.getKey().getKey())
                    .filter(name -> name.chars().allMatch(character -> character < 128))
                    .sorted()
                    .toList();
        }
        return cachedBlockNames;
    }

    private static Optional<Player> player(CommandSourceStack source) {
        return source.getSender() instanceof Player player ? Optional.of(player) : Optional.empty();
    }

    private static String argument(CommandContext<CommandSourceStack> context, String name) {
        return context.getArgument(name, String.class);
    }
}
