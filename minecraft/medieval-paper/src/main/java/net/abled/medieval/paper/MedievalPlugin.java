package net.abled.medieval.paper;

import net.abled.medieval.api.MedievalPlatform;
import net.abled.medieval.core.MedievalCore;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.config.SettingsLoader;
import net.abled.medieval.core.message.MessageService;
import net.abled.medieval.paper.capability.CapabilityReport;
import net.abled.medieval.paper.command.MedievalCommandRegistrar;
import net.abled.medieval.paper.config.PaperSettingsSource;
import net.abled.medieval.paper.message.PaperMessageSource;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point.
 *
 * <p>Responsibilities are deliberately narrow: build the platform bridge, load and validate
 * configuration, wire core services, register commands and report capabilities. Gameplay lives in
 * the core module, which never sees Bukkit.
 */
public final class MedievalPlugin extends JavaPlugin {

    private MedievalCore core;
    private MessageService messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PaperMedievalPlatform platform = new PaperMedievalPlatform(this);
        this.messages = new MessageService(PaperMessageSource.load(this, "messages.yml"));

        MedievalSettings settings = loadSettings(platform);
        this.core = new MedievalCore(platform, settings);
        core.services().register(MessageService.class, messages);
        core.enable();

        new MedievalCommandRegistrar(this, core, messages).register();
        new CapabilityReport(this, platform).log();

        getLogger().info("Medieval " + getPluginMeta().getVersion() + " enabled on " + platform.serverVersion());
        getLogger().info("Settings: " + settings.summary());
        getLogger().info("Loaded " + core.services().size() + " core service(s)");
    }

    @Override
    public void onDisable() {
        if (core != null) {
            core.disable();
            core = null;
        }
    }

    public MedievalCore core() {
        if (core == null) {
            throw new IllegalStateException("Medieval core is not running");
        }
        return core;
    }

    public MessageService messages() {
        return messages;
    }

    /** Re-reads messages.yml so text changes apply without a restart. */
    public void reloadMessages() {
        messages.reload(PaperMessageSource.load(this, "messages.yml"));
    }

    /** Re-reads and validates config.yml, applying the values that are safe to change at runtime. */
    public MedievalSettings reloadSettings() {
        MedievalSettings settings = loadSettings(core().platform());
        core().applySettings(settings);
        return settings;
    }

    private MedievalSettings loadSettings(MedievalPlatform platform) {
        return SettingsLoader.load(PaperSettingsSource.load(this), platform::logWarning);
    }
}
