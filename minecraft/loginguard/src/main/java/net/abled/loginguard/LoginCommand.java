package net.abled.loginguard;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class LoginCommand implements CommandExecutor {

    enum Mode { REGISTER, LOGIN }

    private final LoginManager manager;
    private final Mode mode;

    LoginCommand(LoginManager manager, Mode mode) {
        this.manager = manager;
        this.mode = mode;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("players only");
            return true;
        }
        if (mode == Mode.REGISTER) {
            if (args.length != 2) {
                player.sendMessage("\u00A7cusage: /" + label + " <password> <confirm>");
                return true;
            }
            manager.register(player, args[0], args[1]);
        } else {
            if (args.length != 1) {
                player.sendMessage("\u00A7cusage: /" + label + " <password>");
                return true;
            }
            manager.login(player, args[0]);
        }
        return true;
    }
}
