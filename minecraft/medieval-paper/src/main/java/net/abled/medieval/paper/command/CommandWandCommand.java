package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.core.cmdblock.WandMode;
import net.abled.medieval.core.cmdblock.WandTrigger;
import net.abled.medieval.paper.catalogue.CatalogueHandout;
import net.abled.medieval.paper.cmdblock.CommandWandFactory;
import net.abled.medieval.paper.cmdblock.CommandWandService;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * {@code /medieval cmdblock <mode> <item> <command...>}: bind a console command to a wand item.
 *
 * <p>The command runs as the console when the wand is right-clicked, following its mode:
 * impulse runs once per click, repeating toggles on and off and runs on an interval while on,
 * and chain runs whenever any other wand fires - the fishing rod acting as the redstone that
 * powers it. Every command works, because the dispatch is the console's and no permission check
 * exists between the owner's click and the server.
 *
 * <h2>The gate</h2>
 * Same rule as the catalogue: gated by {@link OwnerGate}, not by a permission or an op check, so
 * a server whose owner is not an operator still works and no other op can use the branch. The
 * {@code requires} predicate keeps it out of tab completion and command listings for everyone
 * else, and the check also runs on execution, so knowing the spelling gains nothing.
 *
 * <h2>The command argument</h2>
 * {@code greedyString} takes the rest of the line, so commands with quoted or multi-word
 * arguments need no escaping. The leading slash is accepted and stripped, because an owner typing
 * {@code /give ...} into a command that dispatches commands is doing the natural thing.
 */
public final class CommandWandCommand {

    public static final String LABEL = "cmdblock";

    private static final String ARG_MODE = "mode";
    private static final String ARG_ITEM = "item";
    private static final String ARG_COMMAND = "command";
    private static final String ARG_TRIGGER = "trigger";

    private final CommandWandService service;
    private final CommandWandFactory factory;
    private final CatalogueHandout handout;
    private final MessageRenderer renderer;
    private final Supplier<OwnerGate> gate;
    private final Logger logger;

    public CommandWandCommand(CommandWandService service, CommandWandFactory factory,
                              CatalogueHandout handout, MessageRenderer renderer,
                              Supplier<OwnerGate> gate, Logger logger) {
        this.service = Objects.requireNonNull(service, "service");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.handout = Objects.requireNonNull(handout, "handout");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The {@code cmdblock} branch, to be attached under {@code /medieval}. */
    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(LABEL)
                .requires(source -> source.getSender() instanceof Player player
                        && gate.get().allows(player.getUniqueId(), player.getName()))
                .executes(context -> usage(context.getSource()))
                .then(Commands.literal("mode")
                        .then(Commands.argument(ARG_MODE, StringArgumentType.word())
                                .suggests(modes())
                                .executes(context -> switchMode(context.getSource(),
                                        context.getArgument(ARG_MODE, String.class)))))
                .then(Commands.literal("trigger")
                        .then(Commands.argument(ARG_TRIGGER, StringArgumentType.word())
                                .suggests(triggers())
                                .executes(context -> switchTrigger(context.getSource(),
                                        context.getArgument(ARG_TRIGGER, String.class)))))
                .then(Commands.argument(ARG_MODE, StringArgumentType.word())
                        .suggests(modes())
                        .then(Commands.argument(ARG_ITEM, StringArgumentType.word())
                                .suggests(items())
                                .then(Commands.argument(ARG_COMMAND, StringArgumentType.greedyString())
                                        .executes(context -> bind(
                                                context.getSource(),
                                                context.getArgument(ARG_MODE, String.class),
                                                context.getArgument(ARG_ITEM, String.class),
                                                context.getArgument(ARG_COMMAND, String.class))))))
                .build();
    }

    /** True when this sender may use the wand commands; used by the help listing. */
    public boolean isOwner(CommandSender sender) {
        return sender instanceof Player player && gate.get().allows(player.getUniqueId(), player.getName());
    }

    private SuggestionProvider<CommandSourceStack> modes() {
        return (context, builder) -> {
            for (String mode : List.of("impulse", "repeating", "chain")) {
                if (mode.startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(mode);
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> items() {
        return (context, builder) -> {
            for (String item : List.of("fishing_rod", "carrot_on_a_stick", "warped_fungus_on_a_stick")) {
                if (item.startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(item);
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> triggers() {
        return (context, builder) -> {
            for (String trigger : List.of("click", "always")) {
                if (trigger.startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(trigger);
                }
            }
            return builder.buildFuture();
        };
    }

    /**
     * The held wand, for the subcommands that act on what the owner is carrying.
     *
     * <p>The main hand only, matching what the listener fires: a wand in the off hand is reported
     * as "nothing held" rather than being switched behind the owner's back.
     */
    private Optional<UUID> heldWand(Player player) {
        return factory.idOf(player.getInventory().getItemInMainHand());
    }

    /** Switches the held wand's mode in place. */
    private int switchMode(CommandSourceStack source, String modeWord) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            return Command.SINGLE_SUCCESS;
        }

        Optional<WandMode.Mode> mode = WandMode.parse(modeWord);
        if (mode.isEmpty()) {
            renderer.send(sender, "cmdblock-bad-mode", Map.of("input", modeWord), true);
            return Command.SINGLE_SUCCESS;
        }
        Optional<UUID> held = heldWand(player);
        if (held.isEmpty()) {
            renderer.send(sender, "cmdblock-no-wand", true);
            return Command.SINGLE_SUCCESS;
        }

        Optional<CommandWandService.Stored> updated = service.setMode(held.get(), mode.get());
        if (updated.isEmpty()) {
            renderer.send(sender, "cmdblock-unbound", true);
            return Command.SINGLE_SUCCESS;
        }

        // The item's label, lore and mode byte must follow the store, or the item lies about what
        // it does.
        CommandWandService.Stored stored = updated.get();
        factory.refresh(player.getInventory().getItemInMainHand(),
                stored.mode(), stored.trigger(), stored.command());
        renderer.send(sender, "cmdblock-mode-set", Map.of(
                "mode", WandMode.displayName(stored.mode())), true);
        logger.info(player.getName() + " switched wand " + held.get() + " to " + stored.mode());
        return Command.SINGLE_SUCCESS;
    }

    /** Switches the held wand's trigger between needs-redstone and always-active. */
    private int switchTrigger(CommandSourceStack source, String triggerWord) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            return Command.SINGLE_SUCCESS;
        }

        Optional<WandTrigger.Trigger> trigger = WandTrigger.parse(triggerWord);
        if (trigger.isEmpty()) {
            renderer.send(sender, "cmdblock-bad-trigger", Map.of("input", triggerWord), true);
            return Command.SINGLE_SUCCESS;
        }
        Optional<UUID> held = heldWand(player);
        if (held.isEmpty()) {
            renderer.send(sender, "cmdblock-no-wand", true);
            return Command.SINGLE_SUCCESS;
        }

        Optional<CommandWandService.Stored> updated = service.setTrigger(held.get(), trigger.get());
        if (updated.isEmpty()) {
            renderer.send(sender, "cmdblock-unbound", true);
            return Command.SINGLE_SUCCESS;
        }

        CommandWandService.Stored stored = updated.get();
        factory.refresh(player.getInventory().getItemInMainHand(),
                stored.mode(), stored.trigger(), stored.command());
        renderer.send(sender, "cmdblock-trigger-set", Map.of(
                "trigger", WandTrigger.displayName(stored.trigger())), true);
        logger.info(player.getName() + " switched wand " + held.get()
                + " to " + stored.trigger());
        return Command.SINGLE_SUCCESS;
    }

    private int usage(CommandSourceStack source) {
        renderer.send(source.getSender(), "cmdblock-usage", true);
        return Command.SINGLE_SUCCESS;
    }

    private int bind(CommandSourceStack source, String modeWord, String itemWord, String commandText) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            // Unreachable through the requires predicate; kept so a future code path cannot hand an
            // item to a sender that has no inventory.
            return Command.SINGLE_SUCCESS;
        }

        Optional<WandMode.Mode> mode = WandMode.parse(modeWord);
        if (mode.isEmpty()) {
            renderer.send(sender, "cmdblock-bad-mode", Map.of("input", modeWord), true);
            return Command.SINGLE_SUCCESS;
        }

        Material material = Material.matchMaterial(itemWord);
        if (material == null || !CommandWandFactory.isWandMaterial(material)) {
            renderer.send(sender, "cmdblock-bad-item", Map.of("input", itemWord), true);
            return Command.SINGLE_SUCCESS;
        }

        String command = commandText.trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isBlank()) {
            renderer.send(sender, "cmdblock-blank-command", true);
            return Command.SINGLE_SUCCESS;
        }

        UUID id = UUID.randomUUID();
        service.register(id, mode.get(), WandTrigger.Trigger.CLICK, command);
        handout.give(player, factory.build(id, mode.get(), WandTrigger.Trigger.CLICK, material, command));

        renderer.send(sender, "cmdblock-bound", Map.of(
                "mode", WandMode.displayName(mode.get()),
                "command", command), true);
        logger.info(player.getName() + " bound a " + WandMode.displayName(mode.get())
                + " command wand (" + material.getKey().getKey() + ", id " + id + ") to: " + command);
        return Command.SINGLE_SUCCESS;
    }
}
