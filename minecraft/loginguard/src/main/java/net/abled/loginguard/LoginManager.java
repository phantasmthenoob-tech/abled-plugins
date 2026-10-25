package net.abled.loginguard;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tracks who is authenticated, decides verdicts (locally or through the bridge), and prompts.
 *
 * <p><b threading contract:</b> command methods ({@link #register}/{@link #login}) are invoked on
 * the server main thread and never block. The bridge round trip is asynchronous: the request is
 * handed to the async scheduler, and only the small state mutations (authenticated set trim,
 * messages) hop back to the main thread when the worker's verdict arrives. Without this, a slow
 * upstream verification would freeze the tick loop - the exact failure verified in production
 * thread dumps on 2026-10-10.
 */
final class LoginManager {

    private final JavaPlugin plugin;
    private final LoginStore store;
    // Chat events fire on an async thread while verdicts mutate on the main thread; a plain
    // HashSet would be cross-thread data corruption in the making.
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    /** Who has a saved record: filled at join (one read), updated on register/login success. */
    private final Set<UUID> knownRecords = ConcurrentHashMap.newKeySet();
    private Consumer<String> log;
    /** One actionable hint per outage, not one per failed /login attempt. */
    private volatile boolean upstreamHintLogged;

    /** Optional bridge; null in local-only mode. */
    private BridgeClient bridge;
    private LoginStore bridgeStore;
    /** Endpoints the worker dials, in order; logged with every UPSTREAM_DOWN so the console
     * names everything that was tried, not just the last twin the worker reports. */
    private volatile java.util.List<String> bridgeEndpoints = java.util.List.of();

    LoginManager(JavaPlugin plugin, LoginStore store) {
        this.plugin = plugin;
        this.store = store;
        this.log = msg -> plugin.getLogger().info(msg);
    }

    JavaPlugin plugin() {
        return plugin;
    }

    void setBridge(BridgeClient client, LoginStore store) {
        this.bridge = client;
        this.bridgeStore = store;
    }

    void setBridgeEndpoints(java.util.List<String> endpoints) {
        this.bridgeEndpoints = endpoints == null ? java.util.List.of() : java.util.List.copyOf(endpoints);
    }

    boolean isAuthenticated(UUID id) {
        return authenticated.contains(id);
    }

    void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        authenticated.remove(id);
        // The single record read per join; the repeating nag task then runs purely from memory.
        if (store.exists(id)) {
            knownRecords.add(id);
        }
        player.sendMessage(promptFor(id));
    }

    /** The gold reminder line for a locked player: login or register. Mainly reused by the nag loop. */
    String promptFor(UUID id) {
        return knownRecords.contains(id) || bridge != null
                ? "\u00A76please /login <password> to play"
                : "\u00A76please /register <password> <confirm> to play";
    }

    void handleQuit(UUID id) {
        authenticated.remove(id);
        knownRecords.remove(id);
    }

    void register(Player player, String password, String confirm) {
        if (!password.equals(confirm)) {
            player.sendMessage("\u00A7cpasswords do not match");
            return;
        }
        if (password.length() < 5 || password.length() > 64) {
            player.sendMessage("\u00A7cchoose a 5-64 character password");
            return;
        }
        if (bridge != null) {
            verifyAsync(player, password, RequestAction.REGISTER);
        } else {
            if (store.exists(player.getUniqueId())) {
                player.sendMessage("\u00A7caccount already exists, use /login");
                return;
            }
            try {
                store.create(player.getUniqueId(), player.getName(), password);
                knownRecords.add(player.getUniqueId());
                authenticated.add(player.getUniqueId());
                player.sendMessage("\u00A7aregistered, welcome");
            } catch (IOException e) {
                player.sendMessage("\u00A7cfailed to save the account, try again");
                plugin.getLogger().warning("registration save failed: " + e.getMessage());
            }
        }
    }

    void login(Player player, String password) {
        if (bridge != null) {
            verifyAsync(player, password, RequestAction.LOGIN);
        } else {
            if (!store.exists(player.getUniqueId())) {
                player.sendMessage("\u00A7cno account, use /register <password> <confirm>");
                return;
            }
            if (store.check(player.getUniqueId(), password)) {
                authenticated.add(player.getUniqueId());
                player.sendMessage("\u00A7awelcome back");
            } else {
                player.sendMessage("\u00A7cwrong password");
            }
        }
    }

    private enum RequestAction { REGISTER, LOGIN }

    /**
     * Drops onto the async scheduler, does the blocking worker exchange there, then hops back to
     * the main tick loop for the outcome. The player is kept locked the whole time.
     */
    private void verifyAsync(Player player, String password, RequestAction action) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        player.sendMessage("\u00A77verifying, hold on...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Verdict verdict = action == RequestAction.REGISTER
                    ? bridge.verify(name, password, LoginGuardPlugin.Mode.REGISTER)
                    : bridge.verify(name, password, LoginGuardPlugin.Mode.LOGIN);
            log.accept("bridge verdict for " + action.toString().toLowerCase() + " of " + name + ": " + verdict
                    + (verdict == Verdict.UPSTREAM_DOWN && !bridgeEndpoints.isEmpty()
                            ? " (tried " + String.join(", ", bridgeEndpoints) + ")" : ""));
            if (verdict == Verdict.UPSTREAM_DOWN) {
                if (!upstreamHintLogged) {
                    upstreamHintLogged = true;
                    String tried = bridgeEndpoints.isEmpty() ? "the configured endpoint"
                            : String.join(", ", bridgeEndpoints);
                    String first = bridgeEndpoints.isEmpty() ? "" : bridgeEndpoints.get(0);
                    log.accept("the worker could not reach the upstream (tried " + tried + ")"
                            + " - run /loginguard probe to test each endpoint and see which ports this"
                            + " machine listens on (" + EndpointProbe.describeListeningPorts() + ")");
                    // The exact trap in the snow.playwithbao.com:50252 report: both the public
                    // address and its 127.0.0.1 twin were refused. A published port is the one
                    // players join on, not the port anything listens on inside the container, so
                    // when the configured endpoint IS this server's own join address the bridge is
                    // pointed at itself and can never answer - it must name the separate auth
                    // server's internal address in bridge.upstream-endpoints instead.
                    if (!first.isBlank()) {
                        log.accept("if " + first + " is how players join THIS server, the bridge is"
                                + " pointed at itself: set bridge.upstream-endpoints to the auth server's"
                                + " real internal address (e.g. 172.x.x.x:25565 or 127.0.0.1:25565 when"
                                + " co-hosted), never this server's public address/port");
                    } else {
                        log.accept("a box's own published port is usually refused from inside it, so a"
                                + " container needs the upstream's internal address in"
                                + " bridge.upstream-endpoints");
                    }
                }
            } else {
                upstreamHintLogged = false;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !player.getUniqueId().equals(id)) {
                    return;
                }
                applyVerdict(player, password, action, verdict);
            });
        });
    }

    /** Main-thread only; applies the bridge verdict to the player. */
    private void applyVerdict(Player player, String password, RequestAction action, Verdict verdict) {
        boolean registerAction = action == RequestAction.REGISTER;
        if (registerAction) {
            switch (verdict) {
                case SUCCESS -> { /* account already exists upstream; accept it as the identity */ }
                case NO_ACCOUNT -> { /* fresh user, allowed to register locally */ }
                default -> {
                    player.sendMessage("\u00A7cverification failed (" + verdict + "); try again later");
                    return;
                }
            }
            try {
                bridgeStore.create(player.getUniqueId(), player.getName(), password);
                knownRecords.add(player.getUniqueId());
            } catch (IOException e) {
                player.sendMessage("\u00A7cfailed to save the local account record, try again");
                log.accept("registration save failed: " + e.getMessage());
                return;
            }
            authenticated.add(player.getUniqueId());
            player.sendMessage("\u00A7aregistered, welcome");
        } else {
            switch (verdict) {
                case SUCCESS -> {
                    authenticated.add(player.getUniqueId());
                    player.sendMessage("\u00A7awelcome back");
                    try {
                        bridgeStore.create(player.getUniqueId(), player.getName(), password);
                        knownRecords.add(player.getUniqueId());
                    } catch (IOException e) {
                        log.accept("failed to refresh login record: " + e.getMessage());
                    }
                }
                case WRONG_PASSWORD -> player.sendMessage("\u00A7cwrong password");
                case NO_ACCOUNT -> player.sendMessage("\u00A7cno account, use /register <password> <confirm>");
                case UPSTREAM_TIMEOUT, UPSTREAM_DOWN -> player.sendMessage("\u00A7cverification unavailable; try again shortly");
            }
        }
    }
}
