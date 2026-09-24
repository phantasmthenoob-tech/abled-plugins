package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.abled.medieval.core.MedievalCore;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.message.MessageService;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.paper.MedievalPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
 * registration in one place and lets a branch exist only for senders that are allowed to see it
 * (see the private administrative branch added later). Tab completion therefore follows the
 * {@code requires} predicates rather than a static permission list.
 */
public final class MedievalCommandRegistrar {

    public static final String PERMISSION_USE = "medieval.command.use";
    public static final String PERMISSION_RELOAD = "medieval.command.reload";

    private final MedievalPlugin plugin;
    private final MedievalCore core;
    private final MessageService messages;

    public MedievalCommandRegistrar(MedievalPlugin plugin, MedievalCore core, MessageService messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.core = Objects.requireNonNull(core, "core");
        this.messages = Objects.requireNonNull(messages, "messages");
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
                            .build(),
                    "Medieval Era server commands",
                    List.of("med"));
        });
    }

    private int help(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        send(sender, messages.raw("help-header"), false);
        send(sender, messages.raw("help-line",
                Map.of("command", "medieval help", "description", "Show this command list")), false);
        send(sender, messages.raw("help-line",
                Map.of("command", "medieval info", "description", "Show medieval system status")), false);
        if (sender.hasPermission(PERMISSION_RELOAD)) {
            send(sender, messages.raw("help-line",
                    Map.of("command", "medieval reload", "description", "Reload messages and configuration")), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandSourceStack source) {
        MedievalSettings settings = core.settings();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("server", core.platform().serverVersion());
        values.put("services", Integer.toString(core.services().size()));
        values.put("deathban", settings.deathban().enabled()
                ? TimeFormat.humanize(settings.deathban().duration())
                : "disabled");
        values.put("nether", settings.dimensions().netherEnabled() ? "open" : "closed");
        values.put("end", settings.dimensions().endEnabled() ? "open" : "closed");
        values.put("siege", settings.siege().enabled() ? "enabled" : "disabled");
        values.put("claims per kingdom", Integer.toString(settings.territory().maxClaimsPerKingdom()));

        CommandSender sender = source.getSender();
        values.forEach((key, value) -> send(sender,
                messages.raw("info-line", Map.of("key", key, "value", value)), false));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PERMISSION_RELOAD)) {
            send(sender, messages.noPermission(), true);
            return Command.SINGLE_SUCCESS;
        }

        long startedAt = System.nanoTime();
        try {
            plugin.reloadMessages();
            MedievalSettings settings = plugin.reloadSettings();
            long millis = (System.nanoTime() - startedAt) / 1_000_000L;

            send(sender, messages.raw("reload-success", Map.of("millis", Long.toString(millis))), true);
            plugin.getLogger().info("Reloaded messages.yml and config.yml in " + millis + " ms ("
                    + settings.summary() + ")");
            plugin.getLogger().info("Live gameplay state (kingdoms, claims, sieges, dimension toggles) is "
                    + "re-read from storage; world generation changes still require a restart.");
        } catch (RuntimeException failure) {
            send(sender, messages.raw("reload-failed",
                    Map.of("reason", String.valueOf(failure.getMessage()))), true);
            plugin.getLogger().log(Level.WARNING, "Reload failed", failure);
        }
        return Command.SINGLE_SUCCESS;
    }

    private void send(CommandSender sender, String miniMessage, boolean withPrefix) {
        String text = withPrefix ? messages.prefix() + miniMessage : miniMessage;
        Component rendered = MiniMessage.miniMessage().deserialize(text);
        sender.sendMessage(rendered);
    }
}
