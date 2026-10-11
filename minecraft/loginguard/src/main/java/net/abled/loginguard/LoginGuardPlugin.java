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
        // Write the bundled config.yml on first run: without this, a missing file leaves every
        // getConfig() read at code defaults (bridge permanently off) and there is no file to edit.
        saveDefaultConfig();

        Path loginsRoot = getDataFolder().toPath().resolve("logins");
        boolean bridgeEnabledEarly = getConfig().getBoolean("bridge.enabled", false);
        String upstreamHostEarly = getConfig().getString("bridge.upstream-host", "127.0.0.1");
        // One subfolder per upstream: logins/<upstream-ip>/<uuid>.login, so records never
        // cross between upstreams and tokens stay where they were verified.
        Path loginsFolder = loginsRoot;
        if (bridgeEnabledEarly) {
            loginsFolder = loginsRoot.resolve(sanitizeFolder(upstreamHostEarly));
            migrateRootRecords(loginsRoot, loginsFolder, upstreamHostEarly);
        }
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
                    msg -> getLogger().info(msg), supervisor::revive), store);
            getLogger().info("bridge mode: verifying against " + upstreamHost + ":" + upstreamPort);
        } else {
            getLogger().info("bridge mode: DISABLED (set bridge.enabled: true in config.yml) ");
        }

        LoginListener listener = new LoginListener(manager, getLogger(), debug);

        // Chat-prompt nagging: every 5 seconds, any still-locked player gets the reminder.
        // Chat nagging: every 5 seconds, purely from memory - the repeating loop must not do
        // per-player disk reads (LoginStore record checks are file reads on the main thread).
        getServer().getScheduler().runTaskTimer(this, () -> getServer().getOnlinePlayers().forEach(player -> {
            if (!manager.isAuthenticated(player.getUniqueId())) {
                player.sendMessage(manager.promptFor(player.getUniqueId()));
            }
        }), 100L, 100L);

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

    /** Safe folder name for a host: dots kept, anything outside [A-Za-z0-9.-] underscored. */
    private static String sanitizeFolder(String host) {
        String cleaned = host.replaceAll("[^A-Za-z0-9.-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    /**
     * Moves flat pre-subfolder records into the upstream folder once. Only files directly in
     * the root (never already-nested ones) are migrated, so restarts are idempotent.
     */
    private void migrateRootRecords(Path root, Path target, String upstreamHost) {
        if (java.nio.file.Files.isDirectory(root)) {
            try (var files = java.nio.file.Files.list(root)) {
                for (Path child : files.filter(p -> p.getFileName().toString().endsWith(".login")).toList()) {
                    java.nio.file.Files.createDirectories(target);
                    java.nio.file.Files.move(child, target.resolve(child.getFileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                getLogger().warning("logins migration into " + target.getFileName() + " failed: " + e.getMessage());
            }
        }
    }
}
