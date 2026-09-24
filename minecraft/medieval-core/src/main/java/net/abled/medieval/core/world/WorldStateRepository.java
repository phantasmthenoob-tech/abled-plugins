package net.abled.medieval.core.world;

import net.abled.medieval.core.storage.Database;
import net.abled.medieval.core.storage.SqlBinder;
import net.abled.medieval.core.storage.StorageLog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted runtime world state (dimension access, event flags).
 *
 * <p>Deliberately kept out of {@code config.yml}: these values are changed while the server runs
 * and must survive a restart, whereas configuration is edited by the owner between restarts.
 */
public final class WorldStateRepository {

    public static final String KEY_NETHER_ENABLED = "dimensions.nether.enabled";
    public static final String KEY_END_ENABLED = "dimensions.end.enabled";

    private static final String SQL_UPSERT = """
            INSERT INTO world_state (state_key, state_value) VALUES (?, ?)
            ON CONFLICT (state_key) DO UPDATE SET state_value = excluded.state_value""";

    private static final String SQL_FIND = "SELECT state_value FROM world_state WHERE state_key = ?";

    private static final String SQL_DELETE = "DELETE FROM world_state WHERE state_key = ?";

    private static final String SQL_LIST = "SELECT state_key, state_value FROM world_state ORDER BY state_key";

    private final Database database;
    private final StorageLog log;

    public WorldStateRepository(Database database, StorageLog log) {
        this.database = Objects.requireNonNull(database, "database");
        this.log = Objects.requireNonNull(log, "log");
    }

    public void put(String key, String value) {
        requireKey(key);
        Objects.requireNonNull(value, "value");
        database.update(SQL_UPSERT, statement -> {
            statement.setString(1, key);
            statement.setString(2, value);
        });
    }

    public void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    public Optional<String> get(String key) {
        requireKey(key);
        return database.queryOne(SQL_FIND, statement -> statement.setString(1, key), row -> row.getString("state_value"));
    }

    public boolean getBoolean(String key, boolean fallback) {
        return get(key).map(value -> "true".equalsIgnoreCase(value)).orElse(fallback);
    }

    public boolean remove(String key) {
        requireKey(key);
        return database.update(SQL_DELETE, statement -> statement.setString(1, key)) > 0;
    }

    public Map<String, String> all() {
        Map<String, String> state = new LinkedHashMap<>();
        database.<Void>queryMany(SQL_LIST, SqlBinder.none(), row -> {
            state.put(row.getString("state_key"), row.getString("state_value"));
            return null;
        });
        return state;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("world state key must not be blank");
        }
    }
}
