package net.abled.medieval.core.admin;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decides who may use the hidden owner commands.
 *
 * <p>The configured value is either a player name (compared case-insensitively) or a UUID. A name
 * is what an owner naturally writes down, but a name is only unique per account on a premium
 * server: on an offline-mode server whoever registers a name first owns it, and a name could be
 * taken before the real owner ever joins. A UUID therefore wins whenever one is configured, and
 * {@link #describe()} reports which rule is in force so the console states the choice instead of
 * leaving it to be discovered by trying the command.
 *
 * <p>This is deliberately not a permission check. The intent is a single named player who does not
 * have to be an operator - an operator check would let every other op in, and a permission would be
 * visible in permission listings. Everything here is plain Java with no server types, so the rule
 * is unit-tested rather than discovered on a live server.
 */
public final class OwnerGate {

    private final Set<UUID> ownerIds;
    private final Set<String> ownerNames;

    private OwnerGate(Set<UUID> ownerIds, Set<String> ownerNames) {
        this.ownerIds = Set.copyOf(ownerIds);
        this.ownerNames = Set.copyOf(ownerNames);
    }

    /** Nobody is an owner. Used when the configuration entry is blank. */
    public static OwnerGate none() {
        return new OwnerGate(Set.of(), Set.of());
    }

    /**
     * Builds the rule from one configuration value: a UUID when it parses as one, otherwise a
     * name. A blank value disables the gate rather than throwing, so a half-finished config entry
     * cannot stop the plugin from starting.
     */
    public static OwnerGate of(String configured) {
        Objects.requireNonNull(configured, "configured");

        Optional<UUID> asId = parseUuid(configured);
        if (asId.isPresent()) {
            return new OwnerGate(Set.of(asId.get()), Set.of());
        }

        String name = configured.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return none();
        }
        return new OwnerGate(Set.of(), Set.of(name));
    }

    /**
     * The full check, for callers that already have both facts (a live player has them and looking
     * them up again would be a needless lookup).
     */
    public boolean allows(UUID playerId, String playerName) {
        Objects.requireNonNull(playerId, "playerId");
        return ownerIds.contains(playerId) || allows(playerName);
    }

    public boolean allows(UUID playerId) {
        return ownerIds.contains(Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean allows(String playerName) {
        if (playerName == null) {
            return false;
        }
        return ownerNames.contains(playerName.trim().toLowerCase(Locale.ROOT));
    }

    /** True when at least one owner is configured; false means the hidden commands are off. */
    public boolean isConfigured() {
        return !ownerIds.isEmpty() || !ownerNames.isEmpty();
    }

    /** Human-readable form for the startup log - never used for a decision. */
    public String describe() {
        if (!ownerIds.isEmpty()) {
            return "UUID " + ownerIds.iterator().next();
        }
        if (!ownerNames.isEmpty()) {
            return "player name '" + ownerNames.iterator().next() + "' (case-insensitive)";
        }
        return "nobody (disabled)";
    }

    /**
     * Accepts a UUID with or without dashes. A value that is neither is not an error: it is simply
     * treated as a player name, which is the more likely intent for anything that is not a UUID.
     */
    private static Optional<UUID> parseUuid(String value) {
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        if (candidate.length() == 36) {
            try {
                return Optional.of(UUID.fromString(candidate));
            } catch (IllegalArgumentException notAUuid) {
                return Optional.empty();
            }
        }

        if (isLikelyBareUuid(candidate)) {
            String dashed = candidate.replaceFirst(
                    "(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
            try {
                return Optional.of(UUID.fromString(dashed));
            } catch (IllegalArgumentException notAUuid) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private static boolean isLikelyBareUuid(String candidate) {
        if (candidate.length() != 32) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (Character.digit(candidate.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
