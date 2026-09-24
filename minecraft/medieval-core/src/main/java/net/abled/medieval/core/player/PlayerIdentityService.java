package net.abled.medieval.core.player;

import net.abled.medieval.api.MedievalService;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Player identity: UUID first, name only as an attribute.
 *
 * <p>Administrative commands resolve names to UUIDs through this service (from the persisted
 * profiles) rather than trusting a name, so ownership never depends on a renameable username.
 */
public final class PlayerIdentityService implements MedievalService {

    private final PlayerRepository repository;

    public PlayerIdentityService(PlayerRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public String name() {
        return "player-identity";
    }

    public void recordLogin(UUID playerId, String name, Instant seenAt) {
        repository.recordLogin(playerId, name, seenAt);
    }

    public Optional<PlayerRecord> find(UUID playerId) {
        return repository.findByUuid(playerId);
    }

    public Optional<UUID> findUuidByName(String name) {
        Objects.requireNonNull(name, "name");
        return repository.findByName(name).map(PlayerRecord::playerId);
    }

    public Optional<String> nameOf(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return repository.findByUuid(playerId).map(PlayerRecord::name);
    }

    /** Number of stored profiles; used for startup logging. */
    public long knownProfiles() {
        return repository.count();
    }
}
