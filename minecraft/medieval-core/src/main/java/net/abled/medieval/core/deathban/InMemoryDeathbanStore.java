package net.abled.medieval.core.deathban;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-persistent {@link DeathbanStore}.
 *
 * <p>Used by unit tests and as an emergency fallback when no database is available. It does NOT
 * survive a restart, so production wiring must use the SQL store — the plugin logs a warning if
 * it ever falls back to this implementation.
 */
public final class InMemoryDeathbanStore implements DeathbanStore {

    private final Map<UUID, DeathbanEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<DeathbanEntry> find(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.ofNullable(entries.get(playerId));
    }

    @Override
    public void save(DeathbanEntry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.playerId(), entry);
    }

    @Override
    public boolean delete(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return entries.remove(playerId) != null;
    }

    @Override
    public Collection<DeathbanEntry> all() {
        return List.copyOf(entries.values());
    }

    @Override
    public int deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        int removed = 0;
        for (Map.Entry<UUID, DeathbanEntry> entry : entries.entrySet()) {
            if (!entry.getValue().isActive(now) && entries.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }
}
