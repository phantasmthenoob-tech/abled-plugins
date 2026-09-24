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

        return new MedievalSettings(deathban, dimensions, siege, territory);
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
