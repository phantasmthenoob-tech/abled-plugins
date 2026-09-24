package net.abled.medieval.core.kingdom;

import net.abled.medieval.api.kingdom.KingdomRank;
import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.MalformedRowException;
import net.abled.medieval.core.storage.SqlBinder;
import net.abled.medieval.core.storage.SqlRows;
import net.abled.medieval.core.storage.StorageException;
import net.abled.medieval.core.storage.StorageLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistence for kingdoms and their memberships. */
public final class KingdomRepository {

    private static final String SQL_INSERT_KINGDOM = """
            INSERT INTO kingdoms (name, tag, founder_id, created_at) VALUES (?, ?, ?, ?)""";

    private static final String SQL_FIND_KINGDOM_BY_ID = """
            SELECT id, name, tag, founder_id, created_at FROM kingdoms WHERE id = ?""";

    private static final String SQL_FIND_KINGDOM_BY_NAME = """
            SELECT id, name, tag, founder_id, created_at FROM kingdoms WHERE name = ? COLLATE NOCASE""";

    private static final String SQL_LIST_KINGDOMS = """
            SELECT id, name, tag, founder_id, created_at FROM kingdoms ORDER BY name COLLATE NOCASE""";

    private static final String SQL_DELETE_KINGDOM = "DELETE FROM kingdoms WHERE id = ?";

    private static final String SQL_UPSERT_MEMBER = """
            INSERT INTO kingdom_members (kingdom_id, player_uuid, rank, joined_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (kingdom_id, player_uuid) DO UPDATE SET rank = excluded.rank""";

    private static final String SQL_DELETE_MEMBER = """
            DELETE FROM kingdom_members WHERE kingdom_id = ? AND player_uuid = ?""";

    private static final String SQL_LIST_MEMBERS = """
            SELECT kingdom_id, player_uuid, rank, joined_at FROM kingdom_members
            WHERE kingdom_id = ? ORDER BY joined_at, player_uuid""";

    private static final String SQL_FIND_KINGDOM_OF_PLAYER = """
            SELECT kingdom_id FROM kingdom_members WHERE player_uuid = ?""";

    private final Database database;
    private final StorageLog log;

    public KingdomRepository(Database database, StorageLog log) {
        this.database = Objects.requireNonNull(database, "database");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Creates a kingdom and its founder membership atomically: either both rows exist or neither
     * does, so a kingdom can never be left without a founder.
     *
     * @throws DuplicateKingdomException when the name is already taken
     */
    public KingdomRecord create(String name, String tag, UUID founderId, Instant createdAt) {
        Objects.requireNonNull(founderId, "founderId");
        Objects.requireNonNull(createdAt, "createdAt");
        requireText(name, "kingdom name");
        requireText(tag, "kingdom tag");

        return database.transaction(active -> {
            long kingdomId;
            try {
                kingdomId = active.insert(SQL_INSERT_KINGDOM, statement -> {
                    statement.setString(1, name);
                    statement.setString(2, tag);
                    statement.setString(3, founderId.toString());
                    statement.setLong(4, createdAt.toEpochMilli());
                });
            } catch (StorageException failure) {
                if (isUniqueViolation(failure)) {
                    throw new DuplicateKingdomException(name, failure);
                }
                throw failure;
            }

            active.update(SQL_UPSERT_MEMBER, statement -> {
                statement.setLong(1, kingdomId);
                statement.setString(2, founderId.toString());
                statement.setString(3, KingdomRank.FOUNDER.name());
                statement.setLong(4, createdAt.toEpochMilli());
            });

            return new KingdomRecord(kingdomId, name, tag, founderId, createdAt);
        });
    }

    public Optional<KingdomRecord> findById(long kingdomId) {
        return database.queryOne(SQL_FIND_KINGDOM_BY_ID,
                statement -> statement.setLong(1, kingdomId),
                KingdomRepository::mapKingdom);
    }

    public Optional<KingdomRecord> findByName(String name) {
        Objects.requireNonNull(name, "name");
        return database.queryOne(SQL_FIND_KINGDOM_BY_NAME,
                statement -> statement.setString(1, name),
                KingdomRepository::mapKingdom);
    }

    public List<KingdomRecord> list() {
        return database.queryMany(SQL_LIST_KINGDOMS, SqlBinder.none(), KingdomRepository::mapKingdom);
    }

    /** Deletes a kingdom and, through the schema's cascades, its members and claims. */
    public boolean delete(long kingdomId) {
        return database.update(SQL_DELETE_KINGDOM, statement -> statement.setLong(1, kingdomId)) > 0;
    }

    public void setMember(long kingdomId, UUID playerId, KingdomRank rank, Instant joinedAt) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(joinedAt, "joinedAt");

        database.update(SQL_UPSERT_MEMBER, statement -> {
            statement.setLong(1, kingdomId);
            statement.setString(2, playerId.toString());
            statement.setString(3, rank.name());
            statement.setLong(4, joinedAt.toEpochMilli());
        });
    }

    public boolean removeMember(long kingdomId, UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return database.update(SQL_DELETE_MEMBER, statement -> {
            statement.setLong(1, kingdomId);
            statement.setString(2, playerId.toString());
        }) > 0;
    }

    /**
     * Lists the members of a kingdom. Rows with an unreadable UUID are skipped with an error log;
     * an unrecognised rank falls back to {@code MEMBER} with a warning, so one corrupt row cannot
     * break the whole roster.
     */
    public List<KingdomMemberRecord> members(long kingdomId) {
        List<KingdomMemberRecord> rows = database.queryMany(SQL_LIST_MEMBERS,
                statement -> statement.setLong(1, kingdomId),
                this::mapMemberOrNull);
        return rows.stream().filter(Objects::nonNull).toList();
    }

    public Optional<Long> kingdomOf(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return database.queryOne(SQL_FIND_KINGDOM_OF_PLAYER,
                statement -> statement.setString(1, playerId.toString()),
                row -> row.getLong("kingdom_id"));
    }

    private KingdomMemberRecord mapMemberOrNull(ResultSet row) throws SQLException {
        UUID playerId;
        try {
            playerId = SqlRows.uuid(row, "player_uuid");
        } catch (MalformedRowException failure) {
            log.error("Skipping a kingdom member row with an unreadable UUID", failure);
            return null;
        }

        String storedRank = row.getString("rank");
        KingdomRank rank = KingdomRank.byName(storedRank).orElse(null);
        if (rank == null) {
            log.warn("Unknown kingdom rank '" + storedRank + "' for " + playerId + "; treating as MEMBER");
            rank = KingdomRank.MEMBER;
        }

        return new KingdomMemberRecord(row.getLong("kingdom_id"), playerId, rank, SqlRows.instant(row, "joined_at"));
    }

    private static KingdomRecord mapKingdom(ResultSet row) throws SQLException {
        return new KingdomRecord(
                row.getLong("id"),
                SqlRows.requiredText(row, "name"),
                SqlRows.requiredText(row, "tag"),
                SqlRows.uuid(row, "founder_id"),
                SqlRows.instant(row, "created_at"));
    }

    private static boolean isUniqueViolation(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        if (message != null && message.contains("UNIQUE constraint failed")) {
            return true;
        }
        if (cause instanceof SQLException sqlFailure) {
            String state = sqlFailure.getSQLState();
            return "23000".equals(state) || "23505".equals(state);
        }
        return false;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
