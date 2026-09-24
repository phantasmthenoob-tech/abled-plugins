package net.abled.medieval.core.world;

import net.abled.medieval.core.config.MedievalSettings;

import java.util.Locale;
import java.util.Optional;

/**
 * A dimension the server can close to players.
 *
 * <p>Only the two gated dimensions are modelled. The overworld is always reachable - it holds the
 * medieval world - so a third constant would carry state nothing ever changes.
 *
 * <p>Each constant owns the three places its state is written: the command name, the persisted-state
 * key and the configuration default. Keeping them together is what stops a new gate from being wired
 * to the wrong key in one of the three.
 */
public enum Dimension {

    NETHER("the Nether", "nether"),
    END("the End", "end");

    private final String displayName;
    private final String id;

    Dimension(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    /** Human-readable name used in messages, for example {@code the Nether}. */
    public String displayName() {
        return displayName;
    }

    /** Command and configuration identifier, for example {@code nether}. */
    public String id() {
        return id;
    }

    /** Key this dimension's runtime state is stored under, shared with {@link WorldStateRepository}. */
    public String stateKey() {
        return switch (this) {
            case NETHER -> WorldStateRepository.KEY_NETHER_ENABLED;
            case END -> WorldStateRepository.KEY_END_ENABLED;
        };
    }

    /**
     * What {@code config.yml} says before anyone has opened or closed the gate at runtime.
     *
     * <p>Read through the settings snapshot rather than cached, so a reload is picked up.
     */
    public boolean configuredOpen(MedievalSettings settings) {
        return switch (this) {
            case NETHER -> settings.dimensions().netherEnabled();
            case END -> settings.dimensions().endEnabled();
        };
    }

    /** Parses a dimension identifier case-insensitively, returning empty for unknown input. */
    public static Optional<Dimension> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toLowerCase(Locale.ROOT);
        for (Dimension dimension : values()) {
            if (dimension.id.equals(normalised) || dimension.name().toLowerCase(Locale.ROOT).equals(normalised)) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }
}
