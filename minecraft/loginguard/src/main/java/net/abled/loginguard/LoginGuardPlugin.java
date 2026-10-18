package net.abled.loginguard;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 *   bind-address: ""          # local source address, when this host owns several
 *   proxy: ""                 # socks5://host:port, so the upstream sees the proxy's address
 * </pre>
 *
 * <p>{@code bind-address} and {@code proxy} decide what the upstream records as the client's
 * address. A TCP source address cannot be forged on a direct connection - the answer has to come
 * back to the address that sent it - so looking like a different address means connecting from
 * one: either another address this host owns, or a proxy/VPN.</p>
 *
 * <p>When the configured endpoint refuses the connection, the worker also tries
 * {@code 127.0.0.1:<upstream-port>} (skipped when that port is this server's own port), which is
 * what makes a server reachable on the host but not through its public IP work unchanged.
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
        // Stamped first: a stale jar left in plugins/ is indistinguishable from a fixed one in
        // the console, and the v1.1.0 dialog build crashed with an NPE in AuthDialogs for weeks.
        String version = getPluginMeta().getVersion();
        getLogger().info("LoginGuard v" + version + " enabling");

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
        String bindAddress = getConfig().getString("bridge.bind-address", "");
        String proxy = getConfig().getString("bridge.proxy", "");

        manager = new LoginManager(this, store);

        if (bridgeEnabled) {
            List<String> endpoints = upstreamEndpoints(upstreamHost, upstreamPort);
            supervisor = new WorkerSupervisor(getDataFolder().toPath(), controlPort,
                    String.join(",", endpoints), upstreamTimeout, bindAddress, proxy,
                    msg -> getLogger().info(msg));
            if (!supervisor.ensureRunning()) {
                getLogger().severe("bridge enabled but the worker could not start; failing closed");
            } else {
                getLogger().info("worker process spawned (upstream " + String.join(",", endpoints) + ")");
            }
            manager.setBridge(new BridgeClient(controlPort, upstreamTimeout + 5000,
                    msg -> getLogger().info(msg), supervisor::revive), store);
            getLogger().info("bridge mode: verifying against " + String.join(",", endpoints));
            // Say what the upstream will see, without ever printing a proxy credential.
            if (proxy != null && !proxy.isBlank()) {
                getLogger().info("bridge connections leave through " + maskProxy(proxy)
                        + " - the upstream sees that address, not this machine's");
            } else if (bindAddress != null && !bindAddress.isBlank()) {
                getLogger().info("bridge connections leave from " + bindAddress.trim()
                        + " - the upstream sees that address");
            } else {
                getLogger().info("bridge connections leave from this machine's default address"
                        + " (set bridge.proxy or bridge.bind-address to change what the upstream records)");
            }
            // Prove the endpoint answers now, not on the first player's /login.
            preflightUpstream(endpoints, bindAddress, proxy);
        } else {
            getLogger().info("bridge mode: DISABLED (set bridge.enabled: true in config.yml) ");
        }

        LoginListener listener = new LoginListener(manager, getLogger(), debug);

        // Nagging: every 5 seconds, any still-locked player gets the reminder, purely from
        // memory - the repeating loop must not do per-player disk reads (LoginStore record
        // checks are file reads on the main thread).
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
        getLogger().info("LoginGuard v" + version + " ready, logins folder: " + loginsFolder);
    }

    @Override
    public void onDisable() {
        if (supervisor != null) {
            supervisor.shutdown();
        }
    }

    /**
     * Primary endpoint plus, when it cannot be this server itself, the loopback twin of the same
     * port. A machine's own public IP is commonly refused from inside its container/host (NAT
     * hairpin) while the same service answers on 127.0.0.1 - and the worker can only report the
     * refusal, never see the difference.
     *
     * <p>The loopback twin is skipped when the configured port is the port this server itself
     * listens on: the verifier would then be pointed at the server it protects, which locks the
     * ghost client and re-enters the bridge against itself instead of authenticating anywhere.
     */
    private List<String> upstreamEndpoints(String upstreamHost, int upstreamPort) {
        List<String> endpoints = new ArrayList<>();
        endpoints.add(upstreamHost + ":" + upstreamPort);
        boolean loopbackHost = isLoopbackHost(upstreamHost);
        if (!loopbackHost && upstreamPort != getServer().getPort()) {
            endpoints.add("127.0.0.1:" + upstreamPort);
        } else if (!loopbackHost) {
            getLogger().warning("upstream " + upstreamHost + ":" + upstreamPort
                    + " uses the very port this server listens on; the loopback fallback is disabled because"
                    + " it would point the verifier at this server itself. If the auth server runs on this"
                    + " machine, set bridge.upstream-port to the port it really listens on.");
        }
        return endpoints;
    }

    private static boolean isLoopbackHost(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
    }

    /**
     * One startup probe, off the main thread: resolve and connect to each endpoint in order and
     * log the first that answers. Without this the only evidence of a bad upstream is a player's
     * failed /login, hours later.
     */
    private void preflightUpstream(List<String> endpoints, String bindAddress, String proxy) {
        // A probe from here says nothing useful about a proxied route: the worker is what dials
        // through the proxy, and guessing at its exit would produce a false "NOT reachable".
        if (proxy != null && !proxy.isBlank()) {
            getLogger().info("upstream preflight skipped: bridge.proxy is set, so the worker performs"
                    + " the check through it - its own connect result is logged as worker: [upstream] ...");
            return;
        }
        String bind = bindAddress == null || bindAddress.isBlank() ? null : bindAddress.trim();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            for (String endpoint : endpoints) {
                int colon = endpoint.lastIndexOf(':');
                String host = endpoint.substring(0, colon);
                int port = Integer.parseInt(endpoint.substring(colon + 1));
                try {
                    InetAddress address = InetAddress.getByName(host);
                    long started = System.nanoTime();
                    try (Socket probe = new Socket()) {
                        if (bind != null) {
                            probe.bind(new InetSocketAddress(bind, 0));
                        }
                        probe.connect(new InetSocketAddress(address, port), 3000);
                    }
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    getLogger().info("upstream " + endpoint + " reachable (" + ms + " ms, "
                            + address.getHostAddress() + ")"
                            + (bind == null ? "" : " from " + bind));
                    if (isLocalAddress(address)) {
                        getLogger().warning("upstream " + endpoint + " is an address of this machine;"
                                + " the auth server must be listening on that port here for the bridge to work");
                    }
                    return;
                } catch (IOException e) {
                    getLogger().warning("upstream " + endpoint + " is NOT reachable: " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")")
                            + " - bridge logins are denied until it answers (fail-closed)");
                }
            }
            getLogger().warning("no bridge upstream endpoint answered; every /login and /register will be denied");
        });
    }

    /** True when the address belongs to this machine (any interface), used for the preflight hint. */
    private static boolean isLocalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(nic -> Collections.list(nic.getInetAddresses()).stream())
                    .anyMatch(address::equals);
        } catch (IOException e) {
            return false;
        }
    }

    /** A proxy spec with any credentials removed, so the console never prints a password. */
    private static String maskProxy(String spec) {
        String trimmed = spec.trim();
        int at = trimmed.lastIndexOf('@');
        if (at < 0) {
            return trimmed;
        }
        int schemeEnd = trimmed.indexOf("://");
        return (schemeEnd < 0 ? "" : trimmed.substring(0, schemeEnd + 3)) + "...@" + trimmed.substring(at + 1);
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
