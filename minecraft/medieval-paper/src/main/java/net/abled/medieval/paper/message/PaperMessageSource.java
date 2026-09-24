package net.abled.medieval.paper.message;

import net.abled.medieval.core.message.MessageSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Optional;

/** {@link MessageSource} backed by a MiniMessage-formatted YAML file. */
public final class PaperMessageSource implements MessageSource {

    private final ConfigurationSection root;

    private PaperMessageSource(ConfigurationSection root) {
        this.root = root;
    }

    /** Reads the given file from the plugin data folder, writing bundled defaults if missing. */
    public static PaperMessageSource load(JavaPlugin plugin, String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }
        return new PaperMessageSource(YamlConfiguration.loadConfiguration(file));
    }

    @Override
    public Optional<String> template(String key) {
        return Optional.ofNullable(root.getString(key));
    }
}
