package net.abled.medieval.core.deathban;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for deathbans.
 *
 * <p>The production implementation is backed by SQLite so the ban survives restarts; keeping the
 * interface this small means the storage engine can change (for example to a shared database on
 * a multi-server network) without touching gameplay code.
 */
public interface DeathbanStore {

    Optional<DeathbanEntry> find(UUID playerId);

    void save(DeathbanEntry entry);

    boolean delete(UUID playerId);

    Collection<DeathbanEntry> all();

    /**
     * Deletes bans that expired at or before {@code now} in a single operation; returns how many
     * rows were removed. Implemented as one statement so periodic cleanup stays cheap.
     */
    int deleteExpired(Instant now);
}
