package net.abled.medieval.core.deathban;

import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.MalformedRowException;
import net.abled.medieval.core.storage.SqlBinder;
import net.abled.medieval.core.storage.SqlRows;
import net.abled.medieval.core.storage.StorageLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable {@link DeathbanStore}.
 *
 * <p>The database is the source of truth: bans are written the moment they are created and read
 * back on login, so a restart or a crash cannot let a banned player back in.
 */
public final class SqlDeathbanStore implements DeathbanStore {

    private static final String SQL_FIND = """
            SELECT uuid, death_at, expires_at, cause, killer FROM deathbans WHERE uuid = ?""";

    private static final String SQL_UPSERT = """
            INSERT INTO deathbans (uuid, death_at, expires_at, cause, killer) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE SET death_at = excluded.death_at, expires_at = excluded.expires_at,
                                             cause = excluded.cause, killer = excluded.killer""";

    private static final String SQL_DELETE = "DELETE FROM deathbans WHERE uuid = ?";

    private static final String SQL_LIST = """
            SELECT uuid, death_at, expires_at, cause, killer FROM deathbans ORDER BY expires_at""";

    private static final String SQL_DELETE_EXPIRED = "DELETE FROM deathbans WHERE expires_at <= ?";

    private final Database database;
    private final StorageLog log;

    public SqlDeathbanStore(Database database, StorageLog log) {
        this.database = Objects.requireNonNull(database, "database");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public Optional<DeathbanEntry> find(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try {
            return database.queryOne(SQL_FIND,
                    statement -> statement.setString(1, playerId.toString()),
                    SqlDeathbanStore::mapEntry);
        } catch (MalformedRowException failure) {
            log.error("Skipping an unreadable deathban row for " + playerId, failure);
            return Optional.empty();
        }
    }

    @Override
    public void save(DeathbanEntry entry) {
        Objects.requireNonNull(entry, "entry");
        database.update(SQL_UPSERT, statement -> {
            statement.setString(1, entry.playerId().toString());
            statement.setLong(2, entry.deathAt().toEpochMilli());
            statement.setLong(3, entry.expiresAt().toEpochMilli());
            statement.setString(4, entry.cause());
            statement.setString(5, entry.killer());
        });
    }

    @Override
    public boolean delete(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return database.update(SQL_DELETE, statement -> statement.setString(1, playerId.toString())) > 0;
    }

    @Override
    public Collection<DeathbanEntry> all() {
        List<DeathbanEntry> entries = database.queryMany(SQL_LIST, SqlBinder.none(), this::mapEntryOrNull);
        return entries.stream().filter(Objects::nonNull).toList();
    }

    @Override
    public int deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return database.update(SQL_DELETE_EXPIRED, statement -> statement.setLong(1, now.toEpochMilli()));
    }

    private DeathbanEntry mapEntryOrNull(ResultSet row) throws SQLException {
        try {
            return mapEntry(row);
        } catch (MalformedRowException failure) {
            log.error("Skipping an unreadable deathban row", failure);
            return null;
        }
    }

    private static DeathbanEntry mapEntry(ResultSet row) throws SQLException {
        return new DeathbanEntry(
                SqlRows.uuid(row, "uuid"),
                SqlRows.instant(row, "death_at"),
                SqlRows.instant(row, "expires_at"),
                row.getString("cause"),
                row.getString("killer"));
    }
}
