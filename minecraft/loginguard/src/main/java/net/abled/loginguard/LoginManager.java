package net.abled.loginguard;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tracks who is authenticated, decides verdicts (locally or through the bridge), and prompts.
 *
 * <p>Bridge mode is optional: with a bridge wired, every register/login first goes upstream; the
 * local {@code logins} record is written on SUCCESS (and refreshed on every successful login) in
 * the same plaintext {@code username:}/{code password:} format as the standalone flow. Without a
 * bridge the original local-only behaviour applies unchanged.
 */
final class LoginManager {

    private final JavaPlugin plugin;
    private final LoginStore store;
    private final Set<UUID> authenticated = new HashSet<>();
    private Consumer<String> log;

    /** Optional bridge; null in local-only mode. */
    private java.util.function.BiFunction<String, String, Optional<Verdict>> bridge;
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
        this.bridge = (username, password) -> {
            if (client == null) {
                return Optional.empty();
            }
            return Optional.of(client.verify(username, password, LoginGuardPlugin.Mode.LOGIN));
        };
        this.bridgeStore = store;
    }

    boolean isAuthenticated(UUID id) {
        return authenticated.contains(id);
    }

    void handleJoin(Player player) {
        authenticated.remove(player.getUniqueId());
        if (store.exists(player.getUniqueId())) {
            if (bridge != null) {
                player.sendMessage("\u00A7eplease /login <password>");
            } else {
                player.sendMessage("\u00A7eplease /login <password>");
            }
        } else {
            player.sendMessage("\u00A7eplease /register <password> <confirm>");
        }
    }

    void handleQuit(UUID id) {
        authenticated.remove(id);
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
            // Bridge register: the upstream decides whether a fresh account can be created. The
            // bridge register path is a plain login against upstream first (NO_ACCOUNT means the
            // username is free to claim locally), then explicit local record creation.
            Verdict verdict = bridge.apply(player.getName(), password).orElse(Verdict.UPSTREAM_DOWN);
            log.accept("bridge verdict for register of " + player.getName() + ": " + verdict);
            switch (verdict) {
                case SUCCESS -> {
                    // already exists upstream; that's fine, accept and record locally
                }
                case NO_ACCOUNT -> { /* fresh registration locally, that's allowed */ }
                default -> {
                    player.sendMessage("\u00A7cverification failed (" + verdict + "); try again later");
                    return;
                }
            }
            try {
                bridgeStore.create(player.getUniqueId(), player.getName(), password);
            } catch (IOException e) {
                player.sendMessage("\u00A7cfailed to save the local account record, try again");
                log.accept("registration save failed: " + e.getMessage());
                return;
            }
            authenticated.add(player.getUniqueId());
            player.sendMessage("\u00A7aregistered, welcome");
        } else {
            if (store.exists(player.getUniqueId())) {
                player.sendMessage("\u00A7caccount already exists, use /login");
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
            Verdict verdict = bridge.apply(player.getName(), password).orElse(Verdict.UPSTREAM_DOWN);
            log.accept("bridge verdict for login of " + player.getName() + ": " + verdict);
            switch (verdict) {
                case SUCCESS -> {
                    authenticated.add(player.getUniqueId());
                    player.sendMessage("\u00A7awelcome back");
                    try {
                        // Refresh the plaintext record on every successful bridge login, per spec.
                        bridgeStore.create(player.getUniqueId(), player.getName(), password);
                    } catch (IOException e) {
                        log.accept("failed to refresh login record: " + e.getMessage());
                    }
                }
                case WRONG_PASSWORD -> player.sendMessage("\u00A7cwrong password");
                case NO_ACCOUNT -> player.sendMessage("\u00A7cno account, use /register <password> <confirm>");
                case UPSTREAM_TIMEOUT, UPSTREAM_DOWN -> player.sendMessage("\u00A7cverification unavailable; try again shortly");
            }
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
}
