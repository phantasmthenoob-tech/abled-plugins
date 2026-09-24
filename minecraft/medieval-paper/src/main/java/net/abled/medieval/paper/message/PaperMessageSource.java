package net.abled.medieval.paper.message;

import net.abled.medieval.core.message.MessageSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/**
 * {@link MessageSource} backed by a MiniMessage-formatted YAML file.
 *
 * <h2>Upgrading an existing server</h2>
 * The file in the data folder is layered <em>over</em> the copy bundled in the jar instead of
 * replacing it. Bukkit writes a bundled resource only when the file is absent and never merges new
 * keys into a file that already exists, so without this layering an updated plugin on an existing
 * server would look up keys its {@code messages.yml} has never heard of, and every one of those
 * messages would render as "Missing message: &lt;key&gt;" - which is what an owner experiences as a
 * GUI or command output full of nothing.
 *
 * <p>Layering keeps all three properties that matter:
 *
 * <ul>
 *   <li>a key added by an update works immediately, with no hand-editing;</li>
 *   <li>the owner's own wording still wins for every key they do have, including one they
 *       deliberately blanked;</li>
 *   <li>the file stays the only thing that is read and written - nothing rewrites or reformats it,
 *       so comments and ordering survive.</li>
 * </ul>
 *
 * <p>A file that is behind is reported once at startup rather than left to be discovered by seeing
 * a broken message in game.
 */
public final class PaperMessageSource implements MessageSource {

    /** Beyond this many stale keys the log line lists a count instead of every name. */
    private static final int MAX_REPORTED_KEYS = 20;

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

        YamlConfiguration configured = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration bundled = bundled(plugin, fileName);
        if (bundled != null) {
            // Reported before the defaults are attached, so "missing" means missing from the file
            // rather than missing now that the bundled copy answers for it.
            reportMissing(plugin, fileName, configured, bundled);
            configured.setDefaults(bundled);
        }
        return new PaperMessageSource(configured);
    }

    @Override
    public Optional<String> template(String key) {
        return Optional.ofNullable(root.getString(key));
    }

    private static YamlConfiguration bundled(JavaPlugin plugin, String fileName) {
        try (InputStream stream = plugin.getResource(fileName)) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not read the bundled " + fileName, failure);
            return null;
        }
    }

    private static void reportMissing(JavaPlugin plugin, String fileName, ConfigurationSection configured,
                                      ConfigurationSection bundled) {
        Set<String> present = configured.getKeys(true);
        List<String> missing = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            if (!bundled.isConfigurationSection(key) && !present.contains(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        String listed = missing.size() <= MAX_REPORTED_KEYS
                ? String.join(", ", missing)
                : missing.size() + " keys, including " + String.join(", ", missing.subList(0, MAX_REPORTED_KEYS));
        plugin.getLogger().info(fileName + " predates this version; the bundled defaults now supply "
                + listed + " (edit or delete those entries in " + fileName + " to adopt the new text)");
    }
}
