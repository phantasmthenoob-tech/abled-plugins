package net.abled.medieval.paper.config;

import net.abled.medieval.core.config.SettingsSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** {@link SettingsSource} backed by the plugin's {@code config.yml}. */
public final class PaperSettingsSource implements SettingsSource {

    public static final String FILE_NAME = "config.yml";

    private final ConfigurationSection root;

    private PaperSettingsSource(ConfigurationSection root) {
        this.root = root;
    }

    /**
     * Reads config.yml from the plugin data folder, writing the bundled defaults first when the
     * file does not exist yet. Reloading re-reads the file from disk.
     */
    public static PaperSettingsSource load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        return new PaperSettingsSource(YamlConfiguration.loadConfiguration(file));
    }

    @Override
    public boolean getBoolean(String path, boolean fallback) {
        return root.getBoolean(path, fallback);
    }

    @Override
    public int getInt(String path, int fallback) {
        return root.getInt(path, fallback);
    }

    @Override
    public long getLong(String path, long fallback) {
        return root.getLong(path, fallback);
    }

    @Override
    public String getString(String path, String fallback) {
        String value = root.getString(path);
        return value == null ? fallback : value;
    }

    @Override
    public boolean has(String path) {
        return root.isSet(path);
    }
}
