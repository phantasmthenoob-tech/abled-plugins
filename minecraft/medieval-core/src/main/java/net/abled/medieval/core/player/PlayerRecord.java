package net.abled.medieval.core.player;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored player profile.
 *
 * <p>The UUID is the authoritative identity; {@code name} exists for display and for
 * administrator lookups and is updated on every login, but nothing in the game rules keys off it.
 */
public record PlayerRecord(UUID playerId, String name, Instant firstSeen, Instant lastSeen) {

    public PlayerRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(firstSeen, "firstSeen");
        Objects.requireNonNull(lastSeen, "lastSeen");
    }
}
