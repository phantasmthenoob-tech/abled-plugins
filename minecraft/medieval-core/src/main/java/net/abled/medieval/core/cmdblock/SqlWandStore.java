package net.abled.medieval.core.cmdblock;

import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.MalformedRowException;
import net.abled.medieval.core.storage.SqlBinder;
import net.abled.medieval.core.storage.StorageLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable storage for command wand bindings.
 *
 * <p>The table is owned here rather than by a schema migration: this store is the only reader and
 * writer, and {@code CREATE TABLE IF NOT EXISTS} run once at startup makes the first run on an
 * existing database identical to a fresh install. The statement executes on a real statement
 * handle at construction - a service that silently skipped this step once is a service that
 * cannot be trusted to have created its table at all, which is exactly how the live
 * "unexpected error" happened.
 */
public final class SqlWandStore {

    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS cmdblock_wands (
                id       TEXT PRIMARY KEY,
                mode     TEXT NOT NULL,
                trigger  TEXT NOT NULL DEFAULT 'CLICK',
                command  TEXT NOT NULL
            )""";

    private static final String SQL_UPSERT = """
            INSERT INTO cmdblock_wands (id, mode, trigger, command) VALUES (?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET mode = excluded.mode, trigger = excluded.trigger,
                                         command = excluded.command""";

    private static final String SQL_FIND = "SELECT mode, trigger, command FROM cmdblock_wands WHERE id = ?";
    private static final String SQL_LIST = "SELECT id, mode, trigger, command FROM cmdblock_wands";

    private final Database database;
    private final StorageLog log;

    /** In-memory view, kept in step with the database on every write. */
    private final ConcurrentHashMap<UUID, WandBinding> cache = new ConcurrentHashMap<>();

    public SqlWandStore(Database database, StorageLog log) {
        this.database = Objects.requireNonNull(database, "database");
        this.log = Objects.requireNonNull(log, "log");

        try {
            database.update(SQL_CREATE, SqlBinder.none());
        } catch (RuntimeException failure) {
            // Reported, not swallowed: a store that could not create its table will fail on every
            // write, and the reason must be in the log where the owner can see it.
            log.error("Could not create the cmdblock_wands table", failure);
        }
    }

    /** Reads every binding into memory; called once, at startup. */
    public void load() {
        try {
            List<Optional<Map.Entry<UUID, WandBinding>>> rows = database.queryMany(SQL_LIST,
                    SqlBinder.none(), row -> {
                        try {
                            return Optional.of(new AbstractMap.SimpleImmutableEntry<>(
                                    UUID.fromString(row.getString("id")),
                                    mapBinding(row)));
                        } catch (MalformedRowException malformed) {
                            log.error("Skipping an unreadable wand row", malformed);
                            return Optional.empty();
                        }
                    });
            for (Optional<Map.Entry<UUID, WandBinding>> row : rows) {
                row.ifPresent(entry -> cache.put(entry.getKey(), entry.getValue()));
            }
        } catch (RuntimeException failure) {
            log.error("Could not read the command wands; none will work until the next restart", failure);
        }
    }

    /**
     * Writes one binding and updates the cache.
     *
     * @return the binding as written
     */
    public WandBinding save(UUID id, WandBinding binding) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(binding, "binding");

        database.update(SQL_UPSERT, statement -> {
            statement.setString(1, id.toString());
            statement.setString(2, binding.mode().name());
            statement.setString(3, binding.trigger().name());
            statement.setString(4, binding.command());
        });
        cache.put(id, binding);
        return binding;
    }

    /** The binding for a wand id, or empty when unknown. */
    public Optional<WandBinding> find(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(cache.get(id));
    }

    /** Every known binding. */
    public java.util.Map<UUID, WandBinding> all() {
        return java.util.Collections.unmodifiableMap(cache);
    }

    private static WandBinding mapBinding(ResultSet row) throws SQLException {
        // A row the parser cannot read becomes MalformedRowException here, so load() reports the
        // specific row and skips it instead of failing the whole startup read.
        String modeWord = row.getString("mode");
        WandMode.Mode mode = WandMode.parse(modeWord)
                .orElseThrow(() -> new MalformedRowException("unknown wand mode: " + modeWord));
        String triggerWord = row.getString("trigger");
        WandTrigger.Trigger trigger = WandTrigger.parse(triggerWord)
                .orElseThrow(() -> new MalformedRowException("unknown wand trigger: " + triggerWord));
        String command = row.getString("command");
        if (command == null || command.isBlank()) {
            throw new MalformedRowException("wand row with a blank command");
        }
        return new WandBinding(mode, trigger, command);
    }
}
