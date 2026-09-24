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
        Land land,
        Admin admin
) {

    /** Who may use the hidden owner catalogue when configuration does not say otherwise. */
    public static final String DEFAULT_SECRET_OWNER = "Disgraced_";

    public MedievalSettings {
        Objects.requireNonNull(deathban, "deathban");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(siege, "siege");
        Objects.requireNonNull(territory, "territory");
        Objects.requireNonNull(land, "land");
        Objects.requireNonNull(admin, "admin");
    }

    /**
     * Gameplay-only view of the settings, with the search limits and the owner defaulted.
     *
     * <p>For callers that have nothing to do with the hidden admin catalogue - tests of kingdom,
     * siege and deathban rules, and any future code that only needs gameplay values - so adding
     * owner configuration did not force a change on every construction site.
     */
    public MedievalSettings(Deathban deathban, Dimensions dimensions, Siege siege, Territory territory) {
        this(deathban, dimensions, siege, territory, Land.defaults(), Admin.defaults());
    }

    /** As above, for callers that do configure the owner but not the search limits. */
    public MedievalSettings(Deathban deathban, Dimensions dimensions, Siege siege, Territory territory,
                            Admin admin) {
        this(deathban, dimensions, siege, territory, Land.defaults(), admin);
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

    /** Limits on {@code /land search}, the closest-block lookup. */
    public record Land(Search search) {

        public Land {
            Objects.requireNonNull(search, "search");
        }

        public static Land defaults() {
            return new Land(Search.defaults());
        }

        /**
         * How far a search may go and how much of a tick it may use.
         *
         * <p>An unbounded search is not a thing that can exist: a block that is not in this world
         * would keep it running forever. So "no matter how far" means a ceiling nobody is expected to
         * reach in normal play, not the absence of one - and both the ceiling and the time limit are
         * reported when they are what stopped a search, rather than being quietly swallowed.
         *
         * @param enabled         whether the command works at all
         * @param maxRadiusBlocks the hard ceiling on how far one search may look, in blocks
         * @param chunksPerTick   chunks one tick may read or load; also how many chunk reads may be in
         *                        flight at once, which bounds both the work and the memory a search uses
         * @param maxDuration     how long one search may run before it gives up
         */
        public record Search(boolean enabled, int maxRadiusBlocks, int chunksPerTick, Duration maxDuration) {

            public static final int DEFAULT_MAX_RADIUS_BLOCKS = 10_000;
            public static final int DEFAULT_CHUNKS_PER_TICK = 4;
            public static final long DEFAULT_MAX_SECONDS = 600L;

            /** Upper bound on {@code chunks-per-tick}; a typo must not let one tick read the world. */
            public static final int MAX_CHUNKS_PER_TICK = 64;

            /**
             * Chunks one tick may consider, including the ones it only checks for existence.
             *
             * <p>Checking whether a chunk has ever been generated is far cheaper than reading one, and
             * most of the map usually has not been generated, so a search spends most of its walk just
             * skipping. Sharing one budget between the two would make an unexplored world crawl for no
             * reason; the multiplier is what keeps it honest in both cases.
             */
            public static final int CANDIDATES_PER_CHUNK_READ = 16;

            public Search {
                Objects.requireNonNull(maxDuration, "maxDuration");
                if (maxRadiusBlocks <= 0) {
                    throw new IllegalArgumentException("search radius must be positive: " + maxRadiusBlocks);
                }
                if (chunksPerTick < 1 || chunksPerTick > MAX_CHUNKS_PER_TICK) {
                    throw new IllegalArgumentException("chunks per tick must be 1.." + MAX_CHUNKS_PER_TICK
                            + " but was " + chunksPerTick);
                }
                if (maxDuration.isZero() || maxDuration.isNegative()) {
                    throw new IllegalArgumentException("search time limit must be positive: " + maxDuration);
                }
            }

            public static Search defaults() {
                return new Search(true, DEFAULT_MAX_RADIUS_BLOCKS, DEFAULT_CHUNKS_PER_TICK,
                        Duration.ofSeconds(DEFAULT_MAX_SECONDS));
            }

            /** Chunks one tick may consider in total, including the ones it only skips over. */
            public int candidatesPerTick() {
                return chunksPerTick * CANDIDATES_PER_CHUNK_READ;
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
                + ", search=" + (land.search().enabled() ? land.search().maxRadiusBlocks() + " blocks" : "off")
                // Console-only: this line goes to the server log, never to a player.
                + ", owner=" + admin.secretOwner();
    }
}
