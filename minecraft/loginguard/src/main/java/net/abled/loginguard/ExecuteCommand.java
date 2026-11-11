package net.abled.loginguard;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /loginguard execute as <username> <host:port> <command>} - logs in upstream as a player
 * who has logged in here before, and runs one command in that authenticated session, the way
 * {@code /execute as <player> run <command>} works locally but for the auth server.
 *
 * <p>The password comes from the saved record, never from the argument list: that is what makes
 * "once a player has logged in before" the gate. No record, no command. The record is read off the
 * main thread, and the whole bridge round trip runs on the async scheduler, so a slow upstream
 * cannot stall the tick loop.
 *
 * <p>Console-safe: the sender gets the verdict and the upstream's reply text, never the password.
 */
final class ExecuteCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final Path loginsRoot;
    private final Supplier<BridgeClient> bridge;
    private final Supplier<List<String>> endpoints;
    /** Port a bare "server ip" is completed with: the configured upstream port. */
    private final int defaultPort;

    ExecuteCommand(JavaPlugin plugin, Path loginsRoot, Supplier<BridgeClient> bridge,
                   Supplier<List<String>> endpoints, int defaultPort) {
        this.plugin = plugin;
        this.loginsRoot = loginsRoot;
        this.bridge = bridge;
        this.endpoints = endpoints;
        this.defaultPort = defaultPort;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("loginguard.admin")) {
            sender.sendMessage("\u00A7cyou do not have loginguard.admin");
            return true;
        }
        ExecuteArgs.Parse parsed = ExecuteArgs.parse(args, defaultPort);
        if (!parsed.ok()) {
            sender.sendMessage("\u00A7c" + parsed.problem());
            return true;
        }
        BridgeClient client = bridge.get();
        if (client == null) {
            sender.sendMessage("\u00A7cexecute needs bridge mode: set bridge.enabled: true in config.yml"
                    + " and restart (local-only records cannot reach another server)");
            return true;
        }
        ExecuteArgs.Parsed request = parsed.parsed();
        // The whole command line, arguments included: an operator debugging a command needs to
        // see what was actually sent, not just its verb. This goes to the sender only; the console
        // line the worker emits stays verb-only.
        sender.sendMessage("\u00A77logging in upstream as " + request.username() + " on "
                + request.endpoint() + " to run " + request.preview() + "...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> report = new ArrayList<>();
            Optional<LoginStore.Stored> found = LoginStore.findByUsername(loginsRoot, request.username());
            if (found.isEmpty()) {
                report.add("\u00A7cno saved login for " + request.username() + " - only a player who"
                        + " has logged in here before can be acted as");
            } else {
                report.addAll(run(client, request, found.get()));
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> report.forEach(sender::sendMessage));
        });
        return true;
    }

    /** The bridge round trip and its report lines. Async thread: touches no Bukkit state. */
    private List<String> run(BridgeClient client, ExecuteArgs.Parsed request, LoginStore.Stored stored) {
        List<String> lines = new ArrayList<>();
        // Records live under the upstream they were verified against. Running on another server is
        // allowed - the operator named it - but the password may not be that account's password
        // there, so say which upstream the record came from instead of letting a reject look odd.
        if (!stored.upstreamFolder().isEmpty()
                && !stored.upstreamFolder().equalsIgnoreCase(sanitizedHostOf(request.endpoint()))) {
            lines.add("\u00A77the saved login for " + stored.username() + " was verified against "
                    + stored.upstreamFolder() + " (record refreshed on its next /login there)");
        }
        BridgeClient.BridgeResult result = client.execute(stored.username(), stored.password(),
                request.endpoint(), request.command(), request.steps().size());
        String detail = result.detail() == null ? "" : result.detail().trim();
        String where = request.username() + " on " + request.endpoint();
        String ran = "ran " + request.preview();
        // A kick or a refusal means the command did not take effect, whatever the verdict says:
        // both are yellow and say so, because a green "ran" over a no-op is worse than no answer.
        boolean notAccepted = CommandReply.looksDropped(detail) || CommandReply.looksRefused(detail);
        switch (result.verdict()) {
            case SUCCESS -> lines.add(notAccepted
                    ? CommandReply.refusedNotice(where, detail)
                    : "\u00A7a" + where + ": " + ran
                            + (detail.isEmpty() ? " (no reply)" : ", " + detail));
            case WRONG_PASSWORD -> lines.add("\u00A7cthe upstream rejected the saved password for "
                    + stored.username() + " - the record is stale; have them /login once to refresh it");
            case NO_ACCOUNT -> lines.add("\u00A7cthe upstream has no account for " + stored.username());
            case UPSTREAM_TIMEOUT -> lines.add("\u00A7c" + where + ": the login never completed ("
                    + (detail.isEmpty() ? "no answer" : detail) + "), so \"" + request.preview()
                    + "\" was NOT run; a ghost session may still be lingering - wait a minute and retry");
            case UPSTREAM_DOWN -> lines.add("\u00A7c" + where + ": the server did not answer"
                    + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }
        if (result.verdict() == Verdict.SUCCESS && notAccepted) {
            // The ghost is that player, not an admin: /setblock and /item answer "unknown command"
            // until the player itself is op upstream, and /op cannot be granted from here because
            // granting it needs a higher level than the player has.
            lines.add("\u00A77that is " + stored.username() + "'s own permission upstream, so the"
                    + " command has to run as an account that is op there. Give it op from that"
                    + " server's console or panel (/op " + stored.username() + "), then retry;"
                    + " \"/op\" itself needs level 3, so it cannot be run from here");
        }
        return lines;
    }

    /** The logins subfolder name for the endpoint's host, matching how records are filed. */
    private static String sanitizedHostOf(String endpoint) {
        String[] parts = EndpointProbe.parse(endpoint);
        return parts == null ? "" : LoginGuardPlugin.sanitizeFolder(parts[0]);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(args[0], List.of("execute"));
        }
        if (args.length == 2) {
            return matching(args[1], List.of("as"));
        }
        if (args.length == 3) {
            List<String> names = new ArrayList<>();
            plugin.getServer().getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return matching(args[2], names);
        }
        if (args.length == 4) {
            return matching(args[3], endpoints.get());
        }
        return List.of();
    }

    private static List<String> matching(String typed, List<String> options) {
        String prefix = typed == null ? "" : typed.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
