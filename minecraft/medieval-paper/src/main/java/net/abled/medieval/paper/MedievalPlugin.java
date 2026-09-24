package net.abled.medieval.paper;

import net.abled.medieval.api.MedievalPlatform;
import net.abled.medieval.api.MedievalScheduler;
import net.abled.medieval.core.MedievalCore;
import net.abled.medieval.core.admin.OwnerGate;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.config.SettingsLoader;
import net.abled.medieval.core.deathban.DeathbanPolicy;
import net.abled.medieval.core.deathban.DeathbanService;
import net.abled.medieval.core.message.MessageService;
import net.abled.medieval.core.player.PlayerIdentityService;
import net.abled.medieval.core.util.TimeFormat;
import net.abled.medieval.core.world.DimensionAccessService;
import net.abled.medieval.paper.capability.CapabilityReport;
import net.abled.medieval.paper.catalogue.CatalogueIndex;
import net.abled.medieval.paper.catalogue.CatalogueListener;
import net.abled.medieval.paper.catalogue.CatalogueSearch;
import net.abled.medieval.paper.catalogue.CatalogueSearchListener;
import net.abled.medieval.paper.command.CatalogueCommand;
import net.abled.medieval.paper.command.DeathbanCommands;
import net.abled.medieval.paper.command.DimensionCommands;
import net.abled.medieval.paper.command.LandCommands;
import net.abled.medieval.paper.command.MedievalCommandRegistrar;
import net.abled.medieval.paper.compat.PaperScheduler;
import net.abled.medieval.paper.config.PaperSettingsSource;
import net.abled.medieval.paper.land.BlockSearchService;
import net.abled.medieval.paper.listener.DeathbanListener;
import net.abled.medieval.paper.listener.DimensionGateListener;
import net.abled.medieval.paper.listener.LoginGateListener;
import net.abled.medieval.paper.message.MessageRenderer;
import net.abled.medieval.paper.message.PaperMessageSource;
import net.abled.medieval.paper.storage.PaperStorage;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.logging.Level;

/**
 * Plugin entry point and composition root.
 *
 * <p>Responsibilities are deliberately narrow: open storage, load and validate configuration, build
 * the services, register listeners and commands, and report capabilities. Gameplay lives in the
 * core module, which never sees Bukkit; the Bukkit-facing behaviour lives in the listeners and
 * command classes, which do not contain rules.
 *
 * <h2>Startup order</h2>
 * storage -&gt; scheduler -&gt; core services -&gt; listeners and commands -&gt; background housekeeping. If
 * storage cannot be opened the plugin disables itself instead of starting without persistence.
 */
public final class MedievalPlugin extends JavaPlugin {

    /** Bans that have expired are swept in the background; logins only ever read a single row. */
    private static final Duration PURGE_INITIAL_DELAY = Duration.ofMinutes(1);
    private static final Duration PURGE_PERIOD = Duration.ofMinutes(10);

    private MedievalCore core;
    private MessageService messages;
    private MessageRenderer renderer;
    private MedievalScheduler scheduler;
    private PaperStorage storage;
    private DimensionAccessService dimensions;
    private DeathbanPolicy deathbanPolicy;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PaperMedievalPlatform platform = new PaperMedievalPlatform(this);
        this.messages = new MessageService(PaperMessageSource.load(this, "messages.yml"));
        this.renderer = new MessageRenderer(messages);
        MedievalSettings settings = loadSettings(platform);

        try {
            this.storage = PaperStorage.open(this);
        } catch (RuntimeException failure) {
            // A persistent game system that cannot reach its database must not start: it would
            // serve empty kingdoms, claims and bans and silently discard every write.
            getLogger().log(Level.SEVERE, "Medieval storage failed to initialise; disabling the plugin "
                    + "instead of running without persistence", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.scheduler = new PaperScheduler(this, message -> getLogger().warning(message));
        this.core = new MedievalCore(platform, settings);

        PlayerIdentityService identities = new PlayerIdentityService(storage.players());

        // The live deathban rules. config.yml supplies the defaults; a toggle or a duration set at
        // runtime is written to world_state and decides from then on, so a switch an administrator
        // flips during an event survives the next restart - which is the whole point of a toggle.
        this.deathbanPolicy = new DeathbanPolicy(core::settings, storage.worldState(),
                message -> getLogger().warning(message));
        deathbanPolicy.load();

        // The deathban service reads its rules through this supplier, so it always sees the live
        // values and never has to know that a policy exists.
        DeathbanService deathbans = DeathbanService.withSystemClock(
                () -> deathbanPolicy.apply(core.settings()), storage.deathbans());

        // The gate reads its persisted state once, before any listener can ask it for an answer.
        this.dimensions = new DimensionAccessService(core::settings, storage.worldState(), core.eventBus(),
                message -> getLogger().warning(message));
        dimensions.load();

        core.services().register(MessageService.class, messages);
        core.services().register(PlayerIdentityService.class, identities);
        core.services().register(DeathbanService.class, deathbans);
        core.services().register(DeathbanPolicy.class, deathbanPolicy);
        core.services().register(DimensionAccessService.class, dimensions);
        core.enable();

        // The hidden owner catalogue. The gate is a supplier so a changed admin.secret-owner takes
        // effect on /medieval reload; the item index is built lazily on the first open, so startup
        // never pays for a listing most restarts are never asked to show.
        CatalogueIndex catalogue = new CatalogueIndex();
        CatalogueCommand catalogueCommands = new CatalogueCommand(catalogue, renderer,
                () -> OwnerGate.of(core.settings().admin().secretOwner()), getLogger());

        // Chat is the only text input Geyser carries to Bedrock, so the catalogue's search asks in
        // chat and the answer is captured from the chat event.
        CatalogueSearch catalogueSearch = new CatalogueSearch(scheduler, renderer);

        // The closest-block search. Its walking happens on one shared tick task rather than one task per
        // search, so ten players searching cost ten slices of a tick, not ten schedulers.
        BlockSearchService blockSearch = new BlockSearchService(scheduler, renderer, core::settings, getLogger());
        blockSearch.start();

        DimensionCommands dimensionCommands = new DimensionCommands(dimensions, scheduler, renderer, getLogger());
        new MedievalCommandRegistrar(this, core, renderer,
                new DeathbanCommands(deathbans, deathbanPolicy, identities, scheduler, renderer, getLogger()),
                dimensionCommands, catalogueCommands,
                new LandCommands(blockSearch, renderer, blockSearch::isEnabled))
                .register();

        getServer().getPluginManager().registerEvents(
                new LoginGateListener(deathbans, identities, renderer, getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new DeathbanListener(deathbans, renderer, getLogger()), this);
        getServer().getPluginManager().registerEvents(
                new DimensionGateListener(dimensions, renderer), this);
        getServer().getPluginManager().registerEvents(
                new CatalogueListener(renderer, catalogueSearch), this);
        getServer().getPluginManager().registerEvents(
                new CatalogueSearchListener(catalogueSearch), this);

        scheduler.runAsyncRepeating(() -> purgeExpiredBans(deathbans), PURGE_INITIAL_DELAY, PURGE_PERIOD);

        new CapabilityReport(this, platform).log();
        getLogger().info("Medieval " + getPluginMeta().getVersion() + " enabled on " + platform.serverVersion());
        getLogger().info("Settings: " + settings.summary());
        getLogger().info("Deathban " + (deathbanPolicy.isEnabled() ? "on" : "off") + " for "
                + TimeFormat.humanize(deathbanPolicy.duration()) + " (" + deathbanPolicy.source() + ")");
        dimensions.snapshot().forEach((dimension, open) -> getLogger().info("Dimension " + dimension.id()
                + ": " + (open ? "open" : "closed")));
        getLogger().info("Loaded " + core.services().size() + " core service(s), "
                + identities.knownProfiles() + " stored player profile(s)");
        MedievalSettings.Land.Search searchLimits = settings.land().search();
        getLogger().info("Block search " + (searchLimits.enabled()
                ? "enabled: up to " + searchLimits.maxRadiusBlocks() + " blocks, "
                        + searchLimits.chunksPerTick() + " chunk(s) per tick, "
                        + TimeFormat.humanize(searchLimits.maxDuration()) + " per search"
                : "disabled"));

        OwnerGate owner = OwnerGate.of(settings.admin().secretOwner());
        if (owner.isConfigured()) {
            getLogger().info("Owner catalogue enabled for " + owner.describe()
                    + " (/medieval " + CatalogueCommand.LABEL + ")");
        } else {
            getLogger().warning("admin.secret-owner is blank, so the owner catalogue is disabled");
        }
    }

    @Override
    public void onDisable() {
        // Order matters: stop background work, then the services, then storage. Closing storage
        // waits for a statement that is already running, so shutting down during a write cannot
        // leave a half-written transaction behind.
        if (scheduler != null) {
            scheduler.cancelRepeating();
            scheduler = null;
        }
        if (core != null) {
            core.disable();
            core = null;
        }
        if (storage != null) {
            storage.close();
            storage = null;
        }
    }

    public MedievalCore core() {
        if (core == null) {
            throw new IllegalStateException("Medieval core is not running");
        }
        return core;
    }

    public PaperStorage storage() {
        if (storage == null) {
            throw new IllegalStateException("Medieval storage is not open");
        }
        return storage;
    }

    public MedievalScheduler scheduler() {
        if (scheduler == null) {
            throw new IllegalStateException("Medieval scheduler is not running");
        }
        return scheduler;
    }

    public MessageService messages() {
        return messages;
    }

    public MessageRenderer renderer() {
        return renderer;
    }

    public DimensionAccessService dimensions() {
        return dimensions;
    }

    /** Re-reads messages.yml so text changes apply without a restart. */
    public void reloadMessages() {
        messages.reload(PaperMessageSource.load(this, "messages.yml"));
    }

    /** Re-reads and validates config.yml, applying the values that are safe to change at runtime. */
    public MedievalSettings reloadSettings() {
        MedievalSettings settings = loadSettings(core().platform());
        core().applySettings(settings);
        // Gates nobody has changed at runtime follow the new configuration; ones that were opened or
        // closed keep the stored decision.
        dimensions.applyConfigDefaults();
        // Same rule for the deathban: a value an administrator set at runtime keeps the stored
        // decision, and a value nobody has touched follows the edited file again.
        deathbanPolicy.applyConfigDefaults();
        return settings;
    }

    private void purgeExpiredBans(DeathbanService deathbans) {
        try {
            int purged = deathbans.purgeExpired();
            if (purged > 0) {
                getLogger().info("Purged " + purged + " expired deathban(s)");
            }
        } catch (RuntimeException failure) {
            // Housekeeping must never take the plugin down; the next run tries again.
            getLogger().log(Level.WARNING, "Could not purge expired deathbans", failure);
        }
    }

    private MedievalSettings loadSettings(MedievalPlatform platform) {
        return SettingsLoader.load(PaperSettingsSource.load(this), platform::logWarning);
    }
}
