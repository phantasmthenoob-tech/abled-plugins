package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.abled.medieval.core.MedievalCore;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.deathban.DeathbanPolicy;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.core.world.Dimension;
import net.abled.medieval.core.world.DimensionAccessService;
import net.abled.medieval.paper.MedievalPlugin;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.command.CommandSender;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Registers {@code /medieval} at runtime through Paper's Brigadier command API.
 *
 * <p>Commands are intentionally not listed in plugin.yml: registering them here keeps command
 * registration in one place, and a branch only exists for senders allowed to see it. Tab completion
 * and command listings therefore follow each branch's {@code requires} predicate instead of a
 * static permission list, so the administrative branches stay invisible to ordinary players.
 *
 * <p>The two dimension gates get their own top-level commands ({@code /nether} and {@code /end})
 * rather than a branch under {@code /medieval}, because staff reach for them in a hurry during an
 * event; both are built from the same code path in {@link DimensionCommands}.
 */
public final class MedievalCommandRegistrar {

    public static final String PERMISSION_USE = "medieval.command.use";
    public static final String PERMISSION_RELOAD = "medieval.command.reload";

    private final MedievalPlugin plugin;
    private final MedievalCore core;
    private final MessageRenderer renderer;
    private final DeathbanCommands deathbanCommands;
    private final DimensionCommands dimensionCommands;
    private final CatalogueCommand catalogueCommands;
    private final LandCommands landCommands;

    public MedievalCommandRegistrar(MedievalPlugin plugin, MedievalCore core, MessageRenderer renderer,
                                    DeathbanCommands deathbanCommands, DimensionCommands dimensionCommands,
                                    CatalogueCommand catalogueCommands, LandCommands landCommands) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.deathbanCommands = Objects.requireNonNull(deathbanCommands, "deathbanCommands");
        this.dimensionCommands = Objects.requireNonNull(dimensionCommands, "dimensionCommands");
        this.catalogueCommands = Objects.requireNonNull(catalogueCommands, "catalogueCommands");
        this.landCommands = Objects.requireNonNull(landCommands, "landCommands");
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(
                    Commands.literal("medieval")
                            .then(Commands.literal("help")
                                    .executes(context -> help(context.getSource())))
                            .then(Commands.literal("info")
                                    .executes(context -> info(context.getSource())))
                            .then(Commands.literal("reload")
                                    .requires(source -> source.getSender().hasPermission(PERMISSION_RELOAD))
                                    .executes(context -> reload(context.getSource())))
                            .then(deathbanCommands.node())
                            // Not listed in plugin.yml and gated by the configured owner rather
                            // than a permission, so it stays invisible to everyone else.
                            .then(catalogueCommands.node())
                            .build(),
                    "Medieval Era server commands",
                    List.of("med"));

            // One short top-level command per gate. Each carries its own permission, so an ordinary
            // player never sees /nether or /end in tab completion or the command list.
            for (Dimension dimension : Dimension.values()) {
                commands.register(dimensionCommands.node(dimension),
                        "Open, close or inspect " + dimension.displayName(),
                        List.of());
            }

            // Player-facing rather than administrative, so it stands on its own rather than under
            // /medieval. Toggle it with land.search.enabled or the permission node.
            commands.register(landCommands.node(),
                    "Find the closest block of a kind", List.of());
        });
    }

    private int help(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        renderer.send(sender, "help-header", false);
        renderer.send(sender, "help-line",
                Map.of("command", "medieval help", "description", "Show this command list"), false);
        renderer.send(sender, "help-line",
                Map.of("command", "medieval info", "description", "Show medieval system status"), false);
        if (sender.hasPermission(PERMISSION_RELOAD)) {
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval reload", "description", "Reload messages and configuration"),
                    false);
        }
        if (sender.hasPermission(DeathbanCommands.PERMISSION)) {
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban check <player>",
                            "description", "Show a player's remaining ban"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban set <player> <duration>",
                            "description", "Banish a player"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban clear <player>",
                            "description", "Lift a player's banishment"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban list",
                            "description", "List every active banishment"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban on|off|toggle",
                            "description", "Turn the deathban on or off at runtime"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban duration <duration>",
                            "description", "Set how long a banishment lasts"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban status",
                            "description", "Show the live deathban rules"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval deathban reset",
                            "description", "Drop the overrides and follow config.yml again"), false);
        }
        if (sender.hasPermission(DimensionCommands.PERMISSION)) {
            renderer.send(sender, "help-line",
                    Map.of("command", "nether open|close|status",
                            "description", "Open or seal the Nether"), false);
            renderer.send(sender, "help-line",
                    Map.of("command", "end open|close|status",
                            "description", "Open or seal the End"), false);
        }
        if (sender.hasPermission(LandCommands.PERMISSION)) {
            renderer.send(sender, "help-line",
                    Map.of("command", "land search <block> [radius]",
                            "description", "Find the closest block of that kind"), false);
        }
        // Only the configured owner sees this line, so /medieval help does not advertise the
        // catalogue to anybody else either.
        if (catalogueCommands.isOwner(sender)) {
            renderer.send(sender, "help-line",
                    Map.of("command", "medieval " + CatalogueCommand.LABEL,
                            "description", "Open the item catalogue"), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandSourceStack source) {
        MedievalSettings settings = core.settings();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("server", core.platform().serverVersion());
        values.put("services", Integer.toString(core.services().size()));
        // The live rules, not config.yml: the toggle and the duration can both be changed at
        // runtime, and an administrator reading /medieval info needs what is actually in force.
        DeathbanPolicy policy = core.services().find(DeathbanPolicy.class).orElse(null);
        values.put("deathban", policy == null
                ? "unknown"
                : policy.isEnabled()
                        ? TimeFormat.humanize(policy.duration()) + " (" + policy.source() + ")"
                        : "disabled (" + policy.source() + ")");
        // The live gate state, not config.yml: a gate can be opened or closed at runtime, and an
        // administrator reading /medieval info needs to see what is actually true right now.
        DimensionAccessService gate = core.services().find(DimensionAccessService.class).orElse(null);
        values.put("nether", describeGate(gate, Dimension.NETHER));
        values.put("end", describeGate(gate, Dimension.END));
        values.put("siege", settings.siege().enabled() ? "enabled" : "disabled");
        values.put("claims per kingdom", Integer.toString(settings.territory().maxClaimsPerKingdom()));

        CommandSender sender = source.getSender();
        values.forEach((key, value) -> renderer.send(sender, "info-line",
                Map.of("key", key, "value", value), false));
        return Command.SINGLE_SUCCESS;
    }

    private static String describeGate(DimensionAccessService gate, Dimension dimension) {
        return gate == null ? "unknown" : gate.isOpen(dimension) ? "open" : "closed";
    }

    private int reload(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PERMISSION_RELOAD)) {
            renderer.send(sender, "no-permission", true);
            return Command.SINGLE_SUCCESS;
        }

        long startedAt = System.nanoTime();
        try {
            plugin.reloadMessages();
            MedievalSettings settings = plugin.reloadSettings();
            long millis = (System.nanoTime() - startedAt) / 1_000_000L;

            renderer.send(sender, "reload-success", Map.of("millis", Long.toString(millis)), true);
            plugin.getLogger().info("Reloaded messages.yml and config.yml in " + millis + " ms ("
                    + settings.summary() + ")");
        } catch (RuntimeException failure) {
            renderer.send(sender, "reload-failed",
                    Map.of("reason", String.valueOf(failure.getMessage())), true);
            plugin.getLogger().log(Level.WARNING, "Reload failed", failure);
        }
        return Command.SINGLE_SUCCESS;
    }
}
