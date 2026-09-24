package net.abled.medieval.paper.capability;

import net.abled.medieval.api.MedievalPlatform;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Detects the optional pieces of the server stack and reports them at startup.
 *
 * <p>Nothing here is required: without Geyser, ViaVersion or Terralith the plugin keeps running
 * on the Java-only path, and the log states exactly which capability is missing instead of
 * failing silently.
 */
public final class CapabilityReport {

    private static final List<Integration> INTEGRATIONS = List.of(
            new Integration("Geyser-Spigot", "Bedrock compatibility layer"),
            new Integration("floodgate", "Bedrock player detection"),
            new Integration("ViaVersion", "newer Java client support"),
            new Integration("ViaBackwards", "older Java client support"));

    private static final String TERRALITH = "terralith";

    private final JavaPlugin plugin;
    private final MedievalPlatform platform;

    public CapabilityReport(JavaPlugin plugin, MedievalPlatform platform) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    public void log() {
        for (Integration integration : INTEGRATIONS) {
            boolean present = platform.isPluginPresent(integration.pluginName());
            plugin.getLogger().info(integration.pluginName() + (present
                    ? " detected - " + integration.purpose() + " enabled"
                    : " not installed - " + integration.purpose() + " unavailable"));
        }
        logTerralith();
    }

    /**
     * Terralith is world-generation infrastructure, not a plugin dependency, so it is detected as
     * a datapack inside each world folder rather than hardcoded anywhere in the plugin.
     */
    private void logTerralith() {
        List<String> found = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            File datapacks = new File(world.getWorldFolder(), "datapacks");
            File[] entries = datapacks.listFiles();
            if (entries == null) {
                continue;
            }
            for (File entry : entries) {
                if (entry.getName().toLowerCase(Locale.ROOT).contains(TERRALITH)) {
                    found.add(world.getName() + "/" + entry.getName());
                }
            }
        }

        if (found.isEmpty()) {
            plugin.getLogger().warning("Terralith datapack not detected in any world folder - "
                    + "review world generation settings before opening the server");
        } else {
            plugin.getLogger().info("Terralith detected: " + String.join(", ", found));
        }
    }

    private record Integration(String pluginName, String purpose) {
    }
}
