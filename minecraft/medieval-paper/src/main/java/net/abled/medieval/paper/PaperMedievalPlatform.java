package net.abled.medieval.paper;

import net.abled.medieval.api.BedrockDetector;
import net.abled.medieval.api.MedievalPlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Paper implementation of {@link MedievalPlatform}.
 *
 * <p>This is the only place where core-visible behaviour touches Bukkit globals. The Bedrock
 * detector defaults to "no Bedrock players" until the Geyser module installs a real one.
 */
public final class PaperMedievalPlatform implements MedievalPlatform {

    private final Plugin plugin;
    private final BedrockDetector bedrockDetector;

    public PaperMedievalPlatform(Plugin plugin) {
        this(plugin, BedrockDetector.none());
    }

    public PaperMedievalPlatform(Plugin plugin, BedrockDetector bedrockDetector) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bedrockDetector = Objects.requireNonNull(bedrockDetector, "bedrockDetector");
    }

    @Override
    public String serverName() {
        return Bukkit.getName();
    }

    @Override
    public String serverVersion() {
        return Bukkit.getVersion();
    }

    @Override
    public boolean isPluginPresent(String pluginName) {
        Plugin installed = Bukkit.getPluginManager().getPlugin(pluginName);
        return installed != null && installed.isEnabled() && installed != plugin;
    }

    @Override
    public boolean isBedrockPlayer(UUID playerId) {
        return bedrockDetector.isBedrockPlayer(playerId);
    }

    @Override
    public void logInfo(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void logWarning(String message) {
        plugin.getLogger().warning(message);
    }

    @Override
    public void logError(String message, Throwable cause) {
        if (cause == null) {
            plugin.getLogger().severe(message);
        } else {
            plugin.getLogger().log(Level.SEVERE, message, cause);
        }
    }
}
