package net.abled.loginguard;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /loginguard probe [host:port]} - tests bridge endpoints from the console, so a wrong
 * host or port is diagnosed in seconds instead of on a player's next /login.
 *
 * <p>Without an argument it probes the endpoints the worker was given, in order. With one it
 * probes that address without touching the config, which is how an operator finds the upstream's
 * real internal address (a published port is rarely the port the server listens on).
 */
final class ProbeCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final Supplier<List<String>> endpoints;
    private final String bindAddress;
    private final boolean proxied;

    ProbeCommand(JavaPlugin plugin, Supplier<List<String>> endpoints, String bindAddress, boolean proxied) {
        this.plugin = plugin;
        this.endpoints = endpoints;
        this.bindAddress = bindAddress == null ? "" : bindAddress.trim();
        this.proxied = proxied;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("loginguard.admin")) {
            sender.sendMessage("\u00A7cyou do not have loginguard.admin");
            return true;
        }
        // The documented form is "/loginguard probe [host:port]", so Bukkit hands this executor
        // ["probe", "<host:port>"] - the literal subcommand word is stripped, not parsed as an
        // endpoint. Without this, the documented form always died with the usage line (two words)
        // or "not host:port: probe" (one word), and only the undocumented bare-host form worked.
        List<String> words = ProbeArgs.commandWords(args);
        if (words.size() > 1) {
            sender.sendMessage("\u00A7cusage: /" + label + " probe [host:port]");
            return true;
        }
        List<String> targets = new ArrayList<>();
        if (words.size() == 1) {
            if (EndpointProbe.parse(words.get(0)) == null) {
                sender.sendMessage("\u00A7cnot host:port: " + words.get(0));
                return true;
            }
            targets.add(words.get(0).trim());
        } else {
            targets.addAll(endpoints.get());
        }
        if (targets.isEmpty()) {
            sender.sendMessage("\u00A7cbridge has no endpoint configured (is bridge.enabled true?)");
            return true;
        }
        sender.sendMessage("\u00A77probing " + String.join(", ", targets) + "...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> report = new ArrayList<>();
            int reachable = 0;
            for (String target : targets) {
                EndpointProbe.Result result = EndpointProbe.probe(target, bindAddress);
                if (result.reachable()) {
                    reachable++;
                }
                report.add("\u00A7" + (result.reachable() ? "a" : "c") + result.describe());
                if (result.reachable() && result.port() == plugin.getServer().getPort()
                        && EndpointProbe.isLocalAddress(result.address())) {
                    report.add("\u00A7cthat is this server itself - the worker cannot log in as a player"
                            + " who is already connected here; the upstream must be the auth server");
                }
            }
            if (reachable == 0) {
                report.add("\u00A77TCP ports this machine listens on: " + EndpointProbe.describeListeningPorts());
                report.add("\u00A77a published port (the one players use) is usually not the port the server"
                        + " listens on inside its container: list the upstream's real internal address,"
                        + " e.g. 127.0.0.1:25565, in bridge.upstream-endpoints");
                report.add("\u00A77if " + targets.get(0) + " is how players join THIS server, the bridge is"
                        + " pointed at itself: use the separate auth server's internal address instead");
            } else {
                report.add("\u00A77put a reachable endpoint first in bridge.upstream-endpoints and restart,"
                        + " so the worker dials it before any other");
            }
            if (proxied) {
                report.add("\u00A77bridge.proxy is set: this probe connected directly, so it says nothing"
                        + " about the route the worker actually uses");
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> report.forEach(sender::sendMessage));
        });
        return true;
    }
}
