package net.abled.loginguard;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * {@code /loginguard <subcommand>} router. {@code execute} is the operator's new subcommand;
 * everything else - including the bare {@code /loginguard host:port} form - keeps going to the
 * probe exactly as before, so no existing habit or console alias changes meaning.
 */
final class LoginguardCommand implements CommandExecutor, TabCompleter {

    private final ProbeCommand probe;
    private final ExecuteCommand execute;

    LoginguardCommand(ProbeCommand probe, ExecuteCommand execute) {
        this.probe = probe;
        this.execute = execute;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("execute")) {
            return execute.onCommand(sender, command, label, args);
        }
        return probe.onCommand(sender, command, label, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            String typed = args[0].toLowerCase();
            return List.of("execute", "probe").stream()
                    .filter(option -> option.startsWith(typed))
                    .toList();
        }
        if (args[0].equalsIgnoreCase("execute")) {
            return execute.onTabComplete(sender, command, alias, args);
        }
        return List.of();
    }
}
