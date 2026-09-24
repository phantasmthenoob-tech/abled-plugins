package net.abled.medieval.core.kingdom;

import net.abled.medieval.api.kingdom.KingdomRank;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Membership of one player in one kingdom. */
public record KingdomMemberRecord(long kingdomId, UUID playerId, KingdomRank rank, Instant joinedAt) {

    public KingdomMemberRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
