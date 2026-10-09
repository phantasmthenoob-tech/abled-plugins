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
        boolean debug = getConfig().getBoolean("debug", false);
        if (debug) {
            getLogger().info("debug logging enabled via config.yml (debug: true)");
        }
        LoginListener listener = new LoginListener(manager, getLogger(), debug);

        // Every 30s: repeat the prompt so an unauthenticated player always has instructions
        // on screen, and (diagnostically) prove the plugin is alive - if these reminders stop,
        // LoginGuard is not enabled, which is itself a lockdown failure worth noticing.
        getServer().getScheduler().runTaskTimer(this, () ->
                getServer().getOnlinePlayers().forEach(player -> {
                    if (!manager.isAuthenticated(player.getUniqueId())) {
                        String note = store.exists(player.getUniqueId())
                                ? "please /login <password>"
                                : "please /register <password> <confirm>";
                        player.sendActionBar(note);
                        player.sendMessage(note);
                    }
                }), 600L, 600L);

        getServer().getPluginManager().registerEvents(listener, this);
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
