package net.abled.loginguard;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;

/**
 * LoginGuard: first join requires /register, later joins require /login. Unauthenticated players
 * cannot move, break, place, open inventories, chat, or run other commands.
 *
 * <p>Bridge mode: instead of checking passwords locally, each submit is verified against the
 * configured upstream server through the headless worker (auth-bridge-worker). Config keys in
 * config.yml:
 *
 * <pre>
 * bridge:
 *   enabled: true
 *   control-port: 4519        # loopback port between plugin and worker
 *   upstream-host: the.host
 *   upstream-port: 25565
 *   upstream-timeout-ms: 15000
 * </pre>
 *
 * <p>With {@code bridge.enabled: false} behaviour falls back to the local logins folder exactly
 * as before. Fail-closed: any bridge outage denies instead of letting people in.
 */
public final class LoginGuardPlugin extends JavaPlugin {

    enum Mode { REGISTER, LOGIN }

    private LoginManager manager;
    private WorkerSupervisor supervisor;

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

        boolean debug = getConfig().getBoolean("debug", false);
        boolean bridgeEnabled = getConfig().getBoolean("bridge.enabled", false);
        int controlPort = getConfig().getInt("bridge.control-port", 4519);
        String upstreamHost = getConfig().getString("bridge.upstream-host", "127.0.0.1");
        int upstreamPort = getConfig().getInt("bridge.upstream-port", 25565);
        long upstreamTimeout = getConfig().getLong("bridge.upstream-timeout-ms", 15000);

        manager = new LoginManager(this, store);

        if (bridgeEnabled) {
            supervisor = new WorkerSupervisor(getDataFolder().toPath(), controlPort, upstreamHost,
                    upstreamPort, upstreamTimeout, msg -> getLogger().info(msg));
            if (!supervisor.ensureRunning()) {
                getLogger().severe("bridge enabled but the worker could not start; failing closed");
            } else {
                getLogger().info("worker process spawned (upstream " + upstreamHost + ":" + upstreamPort + ")");
            }
            manager.setBridge(new BridgeClient(controlPort, upstreamTimeout + 5000,
                    msg -> getLogger().info(msg)), store);
            getLogger().info("bridge mode: verifying against " + upstreamHost + ":" + upstreamPort);
        } else {
            getLogger().info("bridge mode: DISABLED (set bridge.enabled: true in config.yml) ");
        }

        LoginListener listener = new LoginListener(manager, getLogger(), debug);

        getServer().getScheduler().runTaskTimer(this, () ->
                getServer().getOnlinePlayers().forEach(player -> {
                    if (!manager.isAuthenticated(player.getUniqueId())) {
                        String note = (bridgeEnabled || store.exists(player.getUniqueId()))
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
        if (supervisor != null) {
            supervisor.shutdown();
        }
    }
}
