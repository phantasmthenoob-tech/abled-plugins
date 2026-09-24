package net.abled.medieval.core.kingdom;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A persisted kingdom. */
public record KingdomRecord(long id, String name, String tag, UUID founderId, Instant createdAt) {

    public KingdomRecord {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(founderId, "founderId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
