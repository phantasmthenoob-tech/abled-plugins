package net.abled.loginguard;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    private final Set<UUID> authenticated = new HashSet<>();
    private Consumer<String> log;

    /** Optional bridge; null in local-only mode. */
    private BridgeClient bridge;
    private LoginStore bridgeStore;

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

    boolean isAuthenticated(UUID id) {
        return authenticated.contains(id);
    }

    void handleJoin(Player player) {
        authenticated.remove(player.getUniqueId());
        boolean recordExists = store.exists(player.getUniqueId());
        // A screen shown inside the join event races the client's loading state and is dropped;
        // a one-second delay gets the dialog rendered reliably.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (recordExists || bridge != null) {
                AuthDialogs.showLogin(plugin, this, player);
            } else {
                AuthDialogs.showRegister(plugin, this, player);
            }
            // The dialog is on their screen; one chat line names the command fallback.
            player.sendMessage(recordExists || bridge != null
                    ? "\u00A7eor type /login <password>"
                    : "\u00A7eor type /register <password> <confirm>");
        }, 20L);
    }

    /**
     * Puts the right screen back up shortly after a failed attempt (wrong password, mismatch,
     * upstream denial): the submit click closed the dialog, so the locked player is left with
     * nothing but a chat line otherwise.
     */
    private void resendDialog(Player player, boolean loginScreen) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !authenticated.contains(player.getUniqueId())) {
                if (loginScreen) {
                    AuthDialogs.showLogin(plugin, this, player);
                } else {
                    AuthDialogs.showRegister(plugin, this, player);
                }
            }
        }, 20L);
    }

    void handleQuit(UUID id) {
        authenticated.remove(id);
    }

    void register(Player player, String password, String confirm) {
        if (!password.equals(confirm)) {
            player.sendMessage("\u00A7cpasswords do not match");
            resendDialog(player, false);
            return;
        }
        if (password.length() < 5 || password.length() > 64) {
            player.sendMessage("\u00A7cchoose a 5-64 character password");
            resendDialog(player, false);
            return;
        }
        if (bridge != null) {
            verifyAsync(player, password, RequestAction.REGISTER);
        } else {
            if (store.exists(player.getUniqueId())) {
                player.sendMessage("\u00A7caccount already exists, use /login");
                resendDialog(player, false);
                return;
            }
            try {
                store.create(player.getUniqueId(), player.getName(), password);
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
                resendDialog(player, false);
                return;
            }
            if (store.check(player.getUniqueId(), password)) {
                authenticated.add(player.getUniqueId());
                player.sendMessage("\u00A7awelcome back");
            } else {
                player.sendMessage("\u00A7cwrong password");
                resendDialog(player, true);
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
            log.accept("bridge verdict for " + action.toString().toLowerCase() + " of " + name + ": " + verdict);
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
                    resendDialog(player, false);
                    return;
                }
            }
            try {
                bridgeStore.create(player.getUniqueId(), player.getName(), password);
            } catch (IOException e) {
                player.sendMessage("\u00A7cfailed to save the local account record, try again");
                log.accept("registration save failed: " + e.getMessage());
                resendDialog(player, false);
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
                    } catch (IOException e) {
                        log.accept("failed to refresh login record: " + e.getMessage());
                    }
                }
                case WRONG_PASSWORD -> {
                    player.sendMessage("\u00A7cwrong password");
                    resendDialog(player, true);
                }
                case NO_ACCOUNT -> {
                    player.sendMessage("\u00A7cno account, use /register <password> <confirm>");
                    resendDialog(player, false);
                }
                case UPSTREAM_TIMEOUT, UPSTREAM_DOWN -> {
                    player.sendMessage("\u00A7cverification unavailable; try again shortly");
                    resendDialog(player, true);
                }
            }
        }
    }
}
