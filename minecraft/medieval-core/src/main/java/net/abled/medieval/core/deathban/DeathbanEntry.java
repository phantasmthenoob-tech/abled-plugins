package net.abled.medieval.core.deathban;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored deathban.
 *
 * <p>Expiry is an absolute instant, not a countdown, so the remaining time stays correct across
 * server restarts and is unaffected by how long the process was offline.
 */
public record DeathbanEntry(UUID playerId, Instant deathAt, Instant expiresAt, String cause, String killer) {

    public static final String UNKNOWN = "unknown";

    public DeathbanEntry {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(deathAt, "deathAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        cause = (cause == null || cause.isBlank()) ? UNKNOWN : cause;
        killer = (killer == null || killer.isBlank()) ? UNKNOWN : killer;
    }

    public boolean isActive(Instant now) {
        Objects.requireNonNull(now, "now");
        return expiresAt.isAfter(now);
    }

    /** Time left until release; zero once the ban has expired. */
    public Duration remaining(Instant now) {
        Objects.requireNonNull(now, "now");
        return isActive(now) ? Duration.between(now, expiresAt) : Duration.ZERO;
    }
}
