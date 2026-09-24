package net.abled.medieval.core.config;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reads a {@link SettingsSource} into a validated {@link MedievalSettings}.
 *
 * <p>Invalid values never crash startup: they fall back to the documented default and are
 * reported through the warning sink, so a bad config entry is visible in the console instead of
 * being silently applied.
 */
public final class SettingsLoader {

    public static final long DEFAULT_DEATHBAN_SECONDS = 3600L;
    public static final int DEFAULT_RAM_HEALTH = 1000;
    public static final int DEFAULT_RAM_DAMAGE = 100;
    public static final long DEFAULT_RAM_COOLDOWN_SECONDS = 4L;
    public static final int DEFAULT_CATAPULT_HEALTH = 750;
    public static final int DEFAULT_CATAPULT_DAMAGE = 150;
    public static final long DEFAULT_CATAPULT_COOLDOWN_SECONDS = 6L;
    public static final int DEFAULT_MAX_CLAIMS_PER_KINGDOM = 25;
    public static final String DEFAULT_SECRET_OWNER = MedievalSettings.DEFAULT_SECRET_OWNER;

    private SettingsLoader() {
    }

    public static MedievalSettings load(SettingsSource source, Consumer<String> warnings) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(warnings, "warnings");

        MedievalSettings.Deathban deathban = new MedievalSettings.Deathban(
                source.getBoolean("deathban.enabled", true),
                Duration.ofSeconds(positiveLong(source, warnings, "deathban.duration-seconds", DEFAULT_DEATHBAN_SECONDS)));

        MedievalSettings.Dimensions dimensions = new MedievalSettings.Dimensions(
                source.getBoolean("dimensions.nether.enabled", false),
                source.getBoolean("dimensions.end.enabled", false));

        MedievalSettings.Siege siege = new MedievalSettings.Siege(
                source.getBoolean("siege.enabled", true),
                machine(source, warnings, "siege.ram", DEFAULT_RAM_HEALTH, DEFAULT_RAM_DAMAGE, DEFAULT_RAM_COOLDOWN_SECONDS),
                machine(source, warnings, "siege.catapult", DEFAULT_CATAPULT_HEALTH, DEFAULT_CATAPULT_DAMAGE,
                        DEFAULT_CATAPULT_COOLDOWN_SECONDS));

        MedievalSettings.Territory territory = new MedievalSettings.Territory(
                nonNegativeInt(source, warnings, "territory.max-claims-per-kingdom", DEFAULT_MAX_CLAIMS_PER_KINGDOM),
                source.getBoolean("territory.protect-claims", true));

        MedievalSettings.Land land = new MedievalSettings.Land(search(source, warnings));

        MedievalSettings.Admin admin = new MedievalSettings.Admin(secretOwner(source, warnings));

        return new MedievalSettings(deathban, dimensions, siege, territory, land, admin);
    }

    /**
     * The closest-block search limits.
     *
     * <p>{@code chunks-per-tick} is clamped rather than defaulted: an owner raising it for a faster
     * search should get the highest value the plugin will honour, not quietly get the default back and
     * wonder why nothing changed. The other two keep the ordinary "reject and fall back" treatment,
     * because there is no sensible nearest value to clamp them to.
     */
    private static MedievalSettings.Land.Search search(SettingsSource source, Consumer<String> warnings) {
        String path = "land.search";
        MedievalSettings.Land.Search defaults = MedievalSettings.Land.Search.defaults();

        return new MedievalSettings.Land.Search(
                source.getBoolean(path + ".enabled", defaults.enabled()),
                positiveInt(source, warnings, path + ".max-radius", defaults.maxRadiusBlocks()),
                boundedInt(source, warnings, path + ".chunks-per-tick", defaults.chunksPerTick(),
                        MedievalSettings.Land.Search.MAX_CHUNKS_PER_TICK),
                Duration.ofSeconds(positiveLong(source, warnings, path + ".max-seconds",
                        defaults.maxDuration().toSeconds())));
    }

    /**
     * The owner of the hidden catalogue. Unlike the numeric settings this value cannot be "fixed"
     * by falling back silently to a wrong owner - falling back to the documented default is the
     * only safe option, and it is reported so the console states which name is in force.
     */
    private static String secretOwner(SettingsSource source, Consumer<String> warnings) {
        String configured = source.getString("admin.secret-owner", DEFAULT_SECRET_OWNER);
        if (configured == null || configured.isBlank()) {
            warnings.accept("admin.secret-owner must not be blank; using " + DEFAULT_SECRET_OWNER);
            return DEFAULT_SECRET_OWNER;
        }
        return configured.trim();
    }

    private static MedievalSettings.Siege.Machine machine(SettingsSource source, Consumer<String> warnings, String path,
                                                          int defaultHealth, int defaultDamage, long defaultCooldownSeconds) {
        return new MedievalSettings.Siege.Machine(
                positiveInt(source, warnings, path + ".health", defaultHealth),
                nonNegativeInt(source, warnings, path + ".damage", defaultDamage),
                Duration.ofSeconds(nonNegativeLong(source, warnings, path + ".cooldown-seconds", defaultCooldownSeconds)));
    }

    private static int positiveInt(SettingsSource source, Consumer<String> warnings, String path, int fallback) {
        int value = source.getInt(path, fallback);
        if (value <= 0) {
            warnings.accept(path + " must be positive but was " + value + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static int nonNegativeInt(SettingsSource source, Consumer<String> warnings, String path, int fallback) {
        int value = source.getInt(path, fallback);
        if (value < 0) {
            warnings.accept(path + " must not be negative but was " + value + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static int boundedInt(SettingsSource source, Consumer<String> warnings, String path,
                                  int fallback, int maximum) {
        int value = positiveInt(source, warnings, path, fallback);
        if (value > maximum) {
            warnings.accept(path + " must not exceed " + maximum + " but was " + value + "; using " + maximum);
            return maximum;
        }
        return value;
    }

    private static long positiveLong(SettingsSource source, Consumer<String> warnings, String path, long fallback) {
        long value = source.getLong(path, fallback);
        if (value <= 0) {
            warnings.accept(path + " must be positive but was " + value + "; using " + fallback);
            return fallback;
        }
        return value;
    }

    private static long nonNegativeLong(SettingsSource source, Consumer<String> warnings, String path, long fallback) {
        long value = source.getLong(path, fallback);
        if (value < 0) {
            warnings.accept(path + " must not be negative but was " + value + "; using " + fallback);
            return fallback;
        }
        return value;
    }
}
