package net.abled.medieval.core.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable, validated snapshot of the gameplay configuration.
 *
 * <p>Core code never reads YAML directly: the Paper module adapts its configuration into a
 * {@link SettingsSource}, {@link SettingsLoader} validates it, and this record is what the rest
 * of the plugin sees.
 */
public record MedievalSettings(
        Deathban deathban,
        Dimensions dimensions,
        Siege siege,
        Territory territory
) {

    public MedievalSettings {
        Objects.requireNonNull(deathban, "deathban");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(siege, "siege");
        Objects.requireNonNull(territory, "territory");
    }

    /** One hour by default; the ban survives restarts once the SQL store is wired in. */
    public record Deathban(boolean enabled, Duration duration) {

        public Deathban {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("deathban duration must not be negative: " + duration);
            }
        }
    }

    /**
     * Both dimensions are closed by default. State is persisted separately from this file so an
     * event can open them without editing configuration.
     */
    public record Dimensions(boolean netherEnabled, boolean endEnabled) {
    }

    public record Siege(boolean enabled, Machine ram, Machine catapult) {

        public Siege {
            Objects.requireNonNull(ram, "ram");
            Objects.requireNonNull(catapult, "catapult");
        }

        public record Machine(int health, int damage, Duration cooldown) {

            public Machine {
                Objects.requireNonNull(cooldown, "cooldown");
                if (health <= 0) {
                    throw new IllegalArgumentException("machine health must be positive: " + health);
                }
                if (damage < 0) {
                    throw new IllegalArgumentException("machine damage must not be negative: " + damage);
                }
                if (cooldown.isNegative()) {
                    throw new IllegalArgumentException("machine cooldown must not be negative: " + cooldown);
                }
            }
        }
    }

    public record Territory(int maxClaimsPerKingdom, boolean protectClaims) {

        public Territory {
            if (maxClaimsPerKingdom < 0) {
                throw new IllegalArgumentException("max claims must not be negative: " + maxClaimsPerKingdom);
            }
        }
    }

    /** Compact single-line summary used in startup logs and {@code /medieval info}. */
    public String summary() {
        return "deathban=" + (deathban.enabled() ? deathban.duration().toSeconds() + "s" : "disabled")
                + ", nether=" + (dimensions.netherEnabled() ? "open" : "closed")
                + ", end=" + (dimensions.endEnabled() ? "open" : "closed")
                + ", siege=" + (siege.enabled() ? "enabled" : "disabled")
                + ", claims=" + territory.maxClaimsPerKingdom();
    }
}
