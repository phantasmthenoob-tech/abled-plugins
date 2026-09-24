package net.abled.medieval.core.player;

import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.MalformedRowException;
import net.abled.medieval.core.storage.SqlBinder;
import net.abled.medieval.core.storage.SqlRows;
import net.abled.medieval.core.storage.StorageLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistence for player profiles. All statements are prepared and use the UUID as the key. */
public final class PlayerRepository {

    private static final String SQL_FIND_BY_UUID = """
            SELECT uuid, name, first_seen, last_seen FROM players WHERE uuid = ?""";

    private static final String SQL_FIND_BY_NAME = """
            SELECT uuid, name, first_seen, last_seen FROM players WHERE name = ? COLLATE NOCASE""";

    // first_seen is intentionally not updated: it records the first time we ever saw the player.
    private static final String SQL_UPSERT_LOGIN = """
            INSERT INTO players (uuid, name, first_seen, last_seen) VALUES (?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen""";

    private static final String SQL_COUNT = "SELECT COUNT(*) AS total FROM players";

    private final Database database;
    private final StorageLog log;

    public PlayerRepository(Database database, StorageLog log) {
        this.database = Objects.requireNonNull(database, "database");
        this.log = Objects.requireNonNull(log, "log");
    }

    public Optional<PlayerRecord> findByUuid(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            return database.queryOne(SQL_FIND_BY_UUID,
                    statement -> statement.setString(1, playerId.toString()),
                    PlayerRepository::mapRow);
        } catch (MalformedRowException failure) {
            log.error("Skipping an unreadable player row for " + playerId, failure);
            return Optional.empty();
        }
    }

    public Optional<PlayerRecord> findByName(String name) {
        Objects.requireNonNull(name, "name");
        try {
            return database.queryOne(SQL_FIND_BY_NAME,
                    statement -> statement.setString(1, name),
                    PlayerRepository::mapRow);
        } catch (MalformedRowException failure) {
            log.error("Skipping an unreadable player row for name '" + name + "'", failure);
            return Optional.empty();
        }
    }

    /** Records a login: inserts the profile the first time and otherwise refreshes name and last seen. */
    public void recordLogin(UUID playerId, String name, Instant seenAt) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(seenAt, "seenAt");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("player name must not be blank");
        }

        database.update(SQL_UPSERT_LOGIN, statement -> {
            statement.setString(1, playerId.toString());
            statement.setString(2, name);
            statement.setLong(3, seenAt.toEpochMilli());
            statement.setLong(4, seenAt.toEpochMilli());
        });
    }

    public long count() {
        Long total = database.queryOne(SQL_COUNT, SqlBinder.none(), row -> row.getLong("total")).orElse(0L);
        return total == null ? 0L : total;
    }

    private static PlayerRecord mapRow(ResultSet row) throws SQLException {
        return new PlayerRecord(
                SqlRows.uuid(row, "uuid"),
                SqlRows.requiredText(row, "name"),
                SqlRows.instant(row, "first_seen"),
                SqlRows.instant(row, "last_seen"));
    }
}
