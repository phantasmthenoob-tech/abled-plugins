package net.abled.medieval.core.deathban;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of checking a player against their deathban at login time.
 *
 * <p>Kept as a value type so the platform layer only has to render it: the rules (ban active?
 * feature enabled? expired row?) live in {@link DeathbanService} and are unit-tested without a
 * server.
 */
public record AccessDecision(boolean allowed, Duration remaining) {

    public AccessDecision {
        Objects.requireNonNull(remaining, "remaining");
        if (allowed && !remaining.isZero()) {
            throw new IllegalArgumentException("an allowed decision must not carry a remaining time");
        }
        if (!allowed && (remaining.isZero() || remaining.isNegative())) {
            throw new IllegalArgumentException("a denied decision must carry a positive remaining time");
        }
    }

    /**
     * The access-allowed outcome. Named {@code permit} rather than {@code allowed} because a record
     * already generates an {@code allowed()} accessor for the {@code allowed} component, and a
     * static method with that name and a different return type is a compile error.
     */
    public static AccessDecision permit() {
        return new AccessDecision(true, Duration.ZERO);
    }

    public static AccessDecision denied(Duration remaining) {
        return new AccessDecision(false, remaining);
    }

    public boolean denied() {
        return !allowed;
    }
}
