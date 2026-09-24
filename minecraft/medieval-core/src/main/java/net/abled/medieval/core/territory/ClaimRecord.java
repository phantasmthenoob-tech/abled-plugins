package net.abled.medieval.core.territory;

import java.time.Instant;
import java.util.Objects;

/** One claimed chunk, owned by one kingdom. */
public record ClaimRecord(ChunkPosition position, long kingdomId, Instant claimedAt) {

    public ClaimRecord {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(claimedAt, "claimedAt");
    }
}
