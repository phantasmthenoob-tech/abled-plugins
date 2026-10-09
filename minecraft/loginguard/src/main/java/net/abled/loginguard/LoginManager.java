package net.abled.loginguard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks who is authenticated, talks to the store, and prompts.
 */
final class LoginManager {

    private final JavaPlugin plugin;
    private final LoginStore store;
    private final Set<UUID> authenticated = new HashSet<>();

    LoginManager(JavaPlugin plugin, LoginStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    boolean isAuthenticated(UUID id) {
        return authenticated.contains(id);
    }

    void handleJoin(Player player) {
        authenticated.remove(player.getUniqueId());
        if (store.exists(player.getUniqueId())) {
            player.sendMessage("\u00A7eplease /login <password>");
        } else {
            player.sendMessage("\u00A7eplease /register <password> <confirm>");
        }
    }

    void handleQuit(UUID id) {
        authenticated.remove(id);
    }

    void register(Player player, String password, String confirm) {
        if (store.exists(player.getUniqueId())) {
            player.sendMessage("\u00A7caccount already exists, use /login");
            return;
        }
        if (!password.equals(confirm)) {
            player.sendMessage("\u00A7cpasswords do not match");
            return;
        }
        if (password.length() < 5 || password.length() > 64) {
            player.sendMessage("\u00A7cchoose a 5-64 character password");
            return;
        }
        try {
            store.create(player.getUniqueId(), password);
            authenticated.add(player.getUniqueId());
            player.sendMessage("\u00A7aregistered, welcome");
        } catch (IOException | NoSuchAlgorithmException e) {
            player.sendMessage("\u00A7cfailed to save the account, try again");
            plugin.getLogger().warning("registration save failed: " + e.getMessage());
        }
    }

    void login(Player player, String password) {
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
