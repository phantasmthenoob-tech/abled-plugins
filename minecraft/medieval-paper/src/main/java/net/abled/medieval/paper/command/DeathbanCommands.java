package net.abled.medieval.paper.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.deathban.DeathbanEntry;
import net.abled.medieval.core.deathban.DeathbanPolicy;
import net.abled.medieval.core.deathban.DeathbanService;
import net.abled.medieval.core.player.PlayerIdentityService;
import net.abled.medieval.core.util.DurationParser;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.paper.message.MessageRenderer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /medieval deathban ...}: the administrative deathban interface.
 *
 * <p>Two halves. {@code check|set|clear|list} work on individual banishments, and
 * {@code on|off|toggle|duration|status|reset} customize the rule itself - whether the feature is on
 * and how long a banishment lasts. The second half goes through {@link DeathbanPolicy}, so a change
 * is persisted and therefore survives a restart instead of being forgotten by it.
 *
 * <p>Every branch requires {@value #PERMISSION} and is therefore hidden from other senders by
 * Brigadier's {@code requires} predicate - it does not appear in command listings or tab completion
 * for ordinary players.
 *
 * <h2>Threads</h2>
 * Each branch resolves the target on the tick thread (reading the online player list), runs the
 * database work on an asynchronous task, and hands the answer back through
 * {@link MedievalScheduler#runSync} because messaging a player touches server state. A command
 * therefore never blocks the tick thread on disk, and never touches a Bukkit object off it. The one
 * exception is {@code status}, which reports fields the policy already holds and so needs no
 * storage round trip at all.
 */
public final class DeathbanCommands {

    public static final String PERMISSION = "medieval.command.deathban";
    public static final String LABEL = "deathban";

    private static final String ARG_PLAYER = "player";
    private static final String ARG_DURATION = "duration";
    private static final List<String> DURATION_EXAMPLES = List.of("30m", "2h", "6h", "12h", "1d", "3d");

    private final DeathbanService deathbans;
    private final DeathbanPolicy policy;
    private final PlayerIdentityService identities;
    private final MedievalScheduler scheduler;
    private final MessageRenderer renderer;
    private final Logger logger;

    public DeathbanCommands(DeathbanService deathbans, DeathbanPolicy policy, PlayerIdentityService identities,
                            MedievalScheduler scheduler, MessageRenderer renderer, Logger logger) {
        this.deathbans = Objects.requireNonNull(deathbans, "deathbans");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** The {@code deathban} branch, to be attached under {@code /medieval}. */
    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(LABEL)
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.literal("check")
                        .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                                .suggests(playerNames())
                                .executes(context -> check(context.getSource(), argument(context, ARG_PLAYER)))))
                .then(Commands.literal("set")
                        .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                                .suggests(playerNames())
                                .then(Commands.argument(ARG_DURATION, StringArgumentType.word())
                                        .suggests(suggestions(DURATION_EXAMPLES))
                                        .executes(context -> set(context.getSource(),
                                                argument(context, ARG_PLAYER),
                                                argument(context, ARG_DURATION))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                                .suggests(playerNames())
                                .executes(context -> clear(context.getSource(), argument(context, ARG_PLAYER)))))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                // The rule itself: whether the feature is on, and how long a banishment lasts.
                // Each of these is persisted through the policy, so it outlives a restart.
                .then(Commands.literal("on")
                        .executes(context -> setEnabled(context.getSource(), true)))
                .then(Commands.literal("off")
                        .executes(context -> setEnabled(context.getSource(), false)))
                .then(Commands.literal("toggle")
                        .executes(context -> setEnabled(context.getSource(), !policy.isEnabled())))
                .then(Commands.literal("duration")
                        .then(Commands.argument(ARG_DURATION, StringArgumentType.word())
                                .suggests(suggestions(DURATION_EXAMPLES))
                                .executes(context -> setDuration(context.getSource(),
                                        argument(context, ARG_DURATION)))))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("reset")
                        .executes(context -> reset(context.getSource())))
                .build();
    }

    private int check(CommandSourceStack source, String playerName) {
        CommandSender sender = source.getSender();
        Optional<UUID> online = onlineId(playerName);

        offThread(sender, () -> {
            Optional<UUID> target = resolve(playerName, online);
            if (target.isEmpty()) {
                complete(sender, "deathban-unknown-player", Map.of("player", playerName));
                return;
            }

            Optional<DeathbanEntry> ban = deathbans.activeBan(target.get());
            if (ban.isEmpty()) {
                complete(sender, "deathban-not-banned", Map.of("player", playerName));
                return;
            }

            DeathbanEntry entry = ban.get();
            complete(sender, "deathban-check-banned", Map.of(
                    "player", playerName,
                    "remaining", TimeFormat.humanize(entry.remaining(Instant.now())),
                    "source", describeSource(entry)));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int set(CommandSourceStack source, String playerName, String durationText) {
        CommandSender sender = source.getSender();
        Optional<Duration> parsed = DurationParser.parse(durationText);
        if (parsed.isEmpty()) {
            renderer.send(sender, "deathban-invalid-duration", Map.of("usage", DurationParser.usage()), true);
            return Command.SINGLE_SUCCESS;
        }

        Duration duration = parsed.get();
        String staff = staffName(sender);
        Optional<UUID> online = onlineId(playerName);

        offThread(sender, () -> {
            Optional<UUID> target = resolve(playerName, online);
            if (target.isEmpty()) {
                complete(sender, "deathban-unknown-player", Map.of("player", playerName));
                return;
            }

            DeathbanEntry entry = deathbans.banFor(target.get(), duration, "administrator", staff, Instant.now());
            complete(sender, "deathban-set", Map.of(
                    "player", playerName,
                    "duration", TimeFormat.humanize(duration)));
            logger.info(staff + " banished " + playerName + " (" + target.get() + ") for "
                    + TimeFormat.humanize(duration));

            // Also end a session that is already running, on the tick thread.
            endSessionIfOnline(target.get(), entry.remaining(Instant.now()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private int clear(CommandSourceStack source, String playerName) {
        CommandSender sender = source.getSender();
        String staff = staffName(sender);
        Optional<UUID> online = onlineId(playerName);

        offThread(sender, () -> {
            Optional<UUID> target = resolve(playerName, online);
            if (target.isEmpty()) {
                complete(sender, "deathban-unknown-player", Map.of("player", playerName));
                return;
            }

            boolean removed = deathbans.clear(target.get());
            complete(sender, removed ? "deathban-cleared" : "deathban-clear-none",
                    Map.of("player", playerName));
            if (removed) {
                logger.info(staff + " lifted the banishment of " + playerName + " (" + target.get() + ")");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandSourceStack source) {
        CommandSender sender = source.getSender();

        offThread(sender, () -> {
            Instant now = Instant.now();
            List<Map<String, String>> lines = new ArrayList<>();
            for (DeathbanEntry entry : deathbans.activeBans()) {
                lines.add(Map.of(
                        "player", identities.nameOf(entry.playerId()).orElse(entry.playerId().toString()),
                        "source", describeSource(entry),
                        "remaining", TimeFormat.humanize(entry.remaining(now))));
            }

            scheduler.runSync(() -> {
                renderer.send(sender, "deathban-list-header",
                        Map.of("count", Integer.toString(lines.size())), true);
                if (lines.isEmpty()) {
                    renderer.send(sender, "deathban-list-empty", false);
                    return;
                }
                for (Map<String, String> line : lines) {
                    renderer.send(sender, "deathban-list-line", line, false);
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Turns the deathban on or off, or flips it, and records the decision so it survives a restart.
     *
     * <p>Reported as unchanged when the feature is already in the requested state, which is also
     * what keeps "on" on an already-on server from turning a value that still follows
     * {@code config.yml} into a frozen one.
     *
     * <p>Switching it off does not release anyone: the check the login gate runs permits every
     * player while the feature is off, so a stored ban simply stops being enforced - and starts
     * being enforced again if it is switched back on before that ban expires.
     */
    private int setEnabled(CommandSourceStack source, boolean enabled) {
        CommandSender sender = source.getSender();
        String staff = staffName(sender);

        offThread(sender, () -> {
            if (!policy.setEnabled(enabled, staff)) {
                complete(sender, "deathban-toggle-unchanged", Map.of("state", stateOf(enabled)));
                return;
            }

            logger.info(staff + " turned the deathban " + stateOf(enabled)
                    + " (duration " + TimeFormat.humanize(policy.duration()) + ")");
            complete(sender, enabled ? "deathban-enabled" : "deathban-disabled",
                    Map.of("duration", TimeFormat.humanize(policy.duration())));
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Sets how long a banishment lasts, from now on.
     *
     * <p>A ban that is already stored keeps the expiry it was created with, because that expiry is
     * an absolute timestamp - so shortening the duration never silently releases someone who is
     * already serving one. {@code clear} is the command for that.
     */
    private int setDuration(CommandSourceStack source, String durationText) {
        CommandSender sender = source.getSender();
        Optional<Duration> parsed = DurationParser.parse(durationText);
        if (parsed.isEmpty()) {
            renderer.send(sender, "deathban-invalid-duration", Map.of("usage", DurationParser.usage()), true);
            return Command.SINGLE_SUCCESS;
        }

        Duration duration = parsed.get();
        String staff = staffName(sender);

        offThread(sender, () -> {
            if (!policy.setDuration(duration, staff)) {
                complete(sender, "deathban-duration-unchanged",
                        Map.of("duration", TimeFormat.humanize(duration)));
                return;
            }

            logger.info(staff + " set the deathban duration to " + TimeFormat.humanize(duration));
            complete(sender, "deathban-duration-set", Map.of("duration", TimeFormat.humanize(duration)));
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Reports the live rules and where they came from.
     *
     * <p>Answered from the policy's own fields, so this is the one branch that needs no database
     * read and runs entirely on the tick thread.
     */
    private int status(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        renderer.send(sender,
                policy.isEnabled() ? "deathban-status-on" : "deathban-status-off",
                Map.of("duration", TimeFormat.humanize(policy.duration()), "source", policy.source()),
                true);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Drops every runtime override, so both values follow {@code config.yml} again - the undo for a
     * toggle someone flipped for an event and never wants to think about again.
     */
    private int reset(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        String staff = staffName(sender);

        offThread(sender, () -> {
            if (!policy.reset()) {
                complete(sender, "deathban-reset-none", Map.of());
                return;
            }

            logger.info(staff + " cleared the deathban overrides; config.yml decides again");
            complete(sender, "deathban-reset", Map.of(
                    "state", stateOf(policy.isEnabled()),
                    "duration", TimeFormat.humanize(policy.duration())));
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Runs storage work off the tick thread and reports a storage failure to the sender instead of
     * leaving the command silently unfinished. The task bodies below never touch Bukkit state, so a
     * read and a write are handled the same way: only the reply has to come back to the tick thread.
     */
    private void offThread(CommandSender sender, Runnable work) {
        scheduler.runAsync(() -> {
            try {
                work.run();
            } catch (RuntimeException failure) {
                logger.log(Level.SEVERE, "A /medieval " + LABEL + " request failed", failure);
                complete(sender, "storage-unavailable", Map.of());
            }
        });
    }

    private void complete(CommandSender sender, String key, Map<String, String> placeholders) {
        scheduler.runSync(() -> renderer.send(sender, key, placeholders, true));
    }

    private void endSessionIfOnline(UUID playerId, Duration remaining) {
        scheduler.runSync(() -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                online.kick(renderer.renderWithPrefix("deathban-kick",
                        Map.of("remaining", TimeFormat.humanize(remaining))));
            }
        });
    }

    /**
     * Resolution order: the stored profile first, then the online player captured on the tick
     * thread. The database lookup happens on the asynchronous thread; the online lookup already
     * happened. The name is only how staff refer to a UUID - never what ownership is based on.
     */
    private Optional<UUID> resolve(String playerName, Optional<UUID> online) {
        Optional<UUID> stored = identities.findUuidByName(playerName);
        return stored.isPresent() ? stored : online;
    }

    /** Must be called on the tick thread: it reads the online player list. */
    private static Optional<UUID> onlineId(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        return online == null ? Optional.empty() : Optional.of(online.getUniqueId());
    }

    private static String describeSource(DeathbanEntry entry) {
        return DeathbanEntry.UNKNOWN.equals(entry.killer())
                ? entry.cause()
                : entry.cause() + " by " + entry.killer();
    }

    private static String stateOf(boolean enabled) {
        return enabled ? "on" : "off";
    }

    private static String staffName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : "console";
    }

    private static String argument(CommandContext<CommandSourceStack> context, String name) {
        return context.getArgument(name, String.class);
    }

    private static SuggestionProvider<CommandSourceStack> playerNames() {
        return (context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(online.getName());
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> suggestions(List<String> candidates) {
        return (context, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String candidate : candidates) {
                if (candidate.startsWith(remaining)) {
                    builder.suggest(candidate);
                }
            }
            return builder.buildFuture();
        };
    }
}
