package net.abled.medieval.paper.withers;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * The wither systems' tuning, read once at startup from {@code config.yml}.
 *
 * <p>Deliberately a plain value type rather than part of the core settings model: these values
 * only ever reach Bukkit code, so there is nothing for the core to test and nothing to keep off
 * the Bukkit types. A reload re-reads them by rebuilding the managers, which the mount and
 * skeleton code tolerate because every entity relationship is UUID keyed.
 */
public record WitherSettings(
        double normalSpeed,
        double chargedSpeed,
        double verticalSpeed,
        double acceleration,
        double drag,
        double normalExplosionPower,
        double chargedExplosionPower,
        boolean collisionExplosion,
        boolean mountParticles,
        int maxSkeletonsPerPlayer,
        boolean targetPersistence,
        boolean ownerImmunity,
        boolean friendlyFireBetweenOwned) {

    public WitherSettings {
        // All primitives; nothing to null-check. Clamping happens in load().
    }

    public static WitherSettings load(FileConfiguration config) {
        return new WitherSettings(
                clamp(config.getDouble("withers.mount.normal-speed", 1.5), 0.1, 10.0),
                clamp(config.getDouble("withers.mount.charged-speed", 1.5), 0.1, 10.0),
                clamp(config.getDouble("withers.mount.vertical-speed", 1.2), 0.1, 10.0),
                clamp(config.getDouble("withers.mount.acceleration", 0.20), 0.01, 1.0),
                clamp(config.getDouble("withers.mount.drag", 0.85), 0.1, 1.0),
                clamp(config.getDouble("withers.mount.normal-explosion-power", 1.0), 0.0, 8.0),
                clamp(config.getDouble("withers.mount.charged-explosion-power", 2.0), 0.0, 8.0),
                config.getBoolean("withers.mount.collision-explosion", true),
                config.getBoolean("withers.effects.mount-particles", true),
                Math.max(1, config.getInt("withers.skeletons.max-per-player", 20)),
                config.getBoolean("withers.skeletons.target-persistence", true),
                config.getBoolean("withers.skeletons.owner-immunity", true),
                config.getBoolean("withers.skeletons.friendly-fire-between-owned", false));
    }

    /** The horizontal speed cap for the given mount type. */
    public double speed(boolean charged) {
        return charged ? chargedSpeed : normalSpeed;
    }

    /** The explosion yield for the given mount type, in block radii like TNT's power. */
    public double explosionPower(boolean charged) {
        return charged ? chargedExplosionPower : normalExplosionPower;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
