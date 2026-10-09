package net.abled.loginguard;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

/**
 * LoginGuard: first join requires /register, later joins require /login. Unauthenticated players
 * cannot move, break, place, open inventories, chat, or run other commands.
 */
public final class LoginGuardPlugin extends JavaPlugin {

    private LoginManager manager;

    @Override
    public void onEnable() {
        Path loginsFolder = getDataFolder().toPath().resolve("logins");
        LoginStore store;
        try {
            store = new LoginStore(loginsFolder);
        } catch (IOException e) {
            getLogger().severe("cannot create the logins folder: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        manager = new LoginManager(this, store);
        getServer().getPluginManager().registerEvents(new LoginListener(manager), this);

        PluginCommand register = getCommand("register");
        PluginCommand login = getCommand("login");
        if (register != null) {
            register.setExecutor(new LoginCommand(manager, LoginCommand.Mode.REGISTER));
        }
        if (login != null) {
            login.setExecutor(new LoginCommand(manager, LoginCommand.Mode.LOGIN));
        }
        getLogger().info("LoginGuard ready, logins folder: " + loginsFolder);
    }

    @Override
    public void onDisable() {
        // nothing to flush: every record is written atomically when created
    }
}
