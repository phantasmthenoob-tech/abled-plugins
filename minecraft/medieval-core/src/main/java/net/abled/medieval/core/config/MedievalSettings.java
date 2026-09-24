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
        Territory territory,
        Admin admin
) {

    /** Who may use the hidden owner catalogue when configuration does not say otherwise. */
    public static final String DEFAULT_SECRET_OWNER = "Disgraced_";

    public MedievalSettings {
        Objects.requireNonNull(deathban, "deathban");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(siege, "siege");
        Objects.requireNonNull(territory, "territory");
        Objects.requireNonNull(admin, "admin");
    }

    /**
     * Gameplay-only view of the settings, with the owner defaulted.
     *
     * <p>For callers that have nothing to do with the hidden admin catalogue - tests of kingdom,
     * siege and deathban rules, and any future code that only needs gameplay values - so adding
     * owner configuration did not force a change on every construction site.
     */
    public MedievalSettings(Deathban deathban, Dimensions dimensions, Siege siege, Territory territory) {
        this(deathban, dimensions, siege, territory, Admin.defaults());
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

    /**
     * Hidden owner tooling.
     *
     * <p>The value is either a player name or a UUID; {@code OwnerGate} interprets it and explains
     * why a UUID is the better choice on an offline-mode server. A blank value is rejected here
     * rather than silently disabling the tools, because a configuration mistake should be visible in
     * the startup log instead of leaving the owner wondering why their command does nothing.
     */
    public record Admin(String secretOwner) {

        public Admin {
            Objects.requireNonNull(secretOwner, "secretOwner");
            if (secretOwner.isBlank()) {
                throw new IllegalArgumentException("admin.secret-owner must not be blank");
            }
        }

        public static Admin defaults() {
            return new Admin(DEFAULT_SECRET_OWNER);
        }
    }

    /** Compact single-line summary used in startup logs and {@code /medieval info}. */
    public String summary() {
        return "deathban=" + (deathban.enabled() ? deathban.duration().toSeconds() + "s" : "disabled")
                + ", nether=" + (dimensions.netherEnabled() ? "open" : "closed")
                + ", end=" + (dimensions.endEnabled() ? "open" : "closed")
                + ", siege=" + (siege.enabled() ? "enabled" : "disabled")
                + ", claims=" + territory.maxClaimsPerKingdom()
                // Console-only: this line goes to the server log, never to a player.
                + ", owner=" + admin.secretOwner();
    }
}
