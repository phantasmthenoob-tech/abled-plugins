package net.abled.medieval.core.territory;

import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.SqlBinder;
import net.abled.medieval.core.storage.SqlRows;
import net.abled.medieval.core.storage.StorageLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persistence for territory claims. The primary key is the chunk position itself. */
public final class ClaimRepository {

    private static final String SQL_UPSERT_CLAIM = """
            INSERT INTO claims (world, chunk_x, chunk_z, kingdom_id, claimed_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (world, chunk_x, chunk_z)
            DO UPDATE SET kingdom_id = excluded.kingdom_id, claimed_at = excluded.claimed_at""";

    private static final String SQL_DELETE_CLAIM = """
            DELETE FROM claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?""";

    private static final String SQL_FIND_CLAIM = """
            SELECT world, chunk_x, chunk_z, kingdom_id, claimed_at FROM claims
            WHERE world = ? AND chunk_x = ? AND chunk_z = ?""";

    private static final String SQL_COUNT_BY_KINGDOM = """
            SELECT COUNT(*) AS total FROM claims WHERE kingdom_id = ?""";

    private static final String SQL_LIST_BY_KINGDOM = """
            SELECT world, chunk_x, chunk_z, kingdom_id, claimed_at FROM claims
            WHERE kingdom_id = ? ORDER BY world, chunk_x, chunk_z""";

    private static final String SQL_DELETE_BY_KINGDOM = "DELETE FROM claims WHERE kingdom_id = ?";

    private final Database database;
    private final StorageLog log;

    public ClaimRepository(Database database, StorageLog log) {
        this.database = Objects.requireNonNull(database, "database");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Assigns a chunk to a kingdom.
     *
     * @return the previous owner when the chunk was already claimed, empty when it was unclaimed
     */
    public Optional<ClaimRecord> claim(ChunkPosition position, long kingdomId, Instant claimedAt) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(claimedAt, "claimedAt");

        return database.transaction(active -> {
            Optional<ClaimRecord> previous = active.queryOne(SQL_FIND_CLAIM, positionBinder(position),
                    ClaimRepository::mapClaim);
            active.update(SQL_UPSERT_CLAIM, statement -> {
                statement.setString(1, position.world());
                statement.setInt(2, position.chunkX());
                statement.setInt(3, position.chunkZ());
                statement.setLong(4, kingdomId);
                statement.setLong(5, claimedAt.toEpochMilli());
            });
            return previous;
        });
    }

    public boolean unclaim(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        return database.update(SQL_DELETE_CLAIM, positionBinder(position)) > 0;
    }

    public Optional<ClaimRecord> find(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        return database.queryOne(SQL_FIND_CLAIM, positionBinder(position), ClaimRepository::mapClaim);
    }

    public int countByKingdom(long kingdomId) {
        Integer total = database.queryOne(SQL_COUNT_BY_KINGDOM,
                statement -> statement.setLong(1, kingdomId),
                row -> row.getInt("total")).orElse(0);
        return total == null ? 0 : total;
    }

    public List<ClaimRecord> byKingdom(long kingdomId) {
        return database.queryMany(SQL_LIST_BY_KINGDOM,
                statement -> statement.setLong(1, kingdomId),
                ClaimRepository::mapClaim);
    }

    /** Releases every claim of a kingdom; returns how many were released. */
    public int releaseAll(long kingdomId) {
        return database.update(SQL_DELETE_BY_KINGDOM, statement -> statement.setLong(1, kingdomId));
    }

    private static SqlBinder positionBinder(ChunkPosition position) {
        return statement -> {
            statement.setString(1, position.world());
            statement.setInt(2, position.chunkX());
            statement.setInt(3, position.chunkZ());
        };
    }

    private static ClaimRecord mapClaim(ResultSet row) throws SQLException {
        return new ClaimRecord(
                new ChunkPosition(SqlRows.requiredText(row, "world"), row.getInt("chunk_x"), row.getInt("chunk_z")),
                row.getLong("kingdom_id"),
                SqlRows.instant(row, "claimed_at"));
    }
}
