package net.abled.medieval.core.storage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Applies schema migrations in order and records what has been applied.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>The {@code schema_version} table is created on demand and bootstraps version tracking.</li>
 *   <li>Each pending migration runs inside one transaction together with its version row, so a
 *       crash mid-migration leaves the database on the previous version.</li>
 *   <li>Already applied migrations are skipped, which makes running this on every startup safe.</li>
 *   <li>A database whose recorded version is unknown to this build (a downgrade) aborts startup
 *       instead of running against an unexpected schema.</li>
 * </ul>
 */
public final class SchemaMigrator {

    private static final String SQL_CREATE_VERSION_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL
            )""";
    private static final String SQL_INSERT_VERSION =
            "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)";
    private static final String SQL_SELECT_VERSIONS =
            "SELECT version FROM schema_version ORDER BY version";

    private final StorageLog log;

    public SchemaMigrator(StorageLog log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Brings the database up to date and returns the versions recorded afterwards, oldest first.
     */
    public List<String> migrate(Database database, List<Migration> migrations) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(migrations, "migrations");

        List<Migration> ordered = new ArrayList<>(migrations);
        ordered.sort(Comparator.comparing(Migration::version));
        requireUniqueVersions(ordered);

        database.update(SQL_CREATE_VERSION_TABLE, SqlBinder.none());
        List<String> applied = appliedVersions(database);

        Set<String> known = new HashSet<>();
        for (Migration migration : ordered) {
            known.add(migration.version());
        }
        for (String version : applied) {
            if (!known.contains(version)) {
                throw new StorageException("database schema version '" + version + "' is newer than this build "
                        + "understands; refusing to start rather than run against an unknown schema");
            }
        }

        for (Migration migration : ordered) {
            if (applied.contains(migration.version())) {
                continue;
            }
            apply(database, migration);
        }

        return appliedVersions(database);
    }

    private void apply(Database database, Migration migration) {
        database.transaction(active -> {
            for (String statement : migration.statements()) {
                active.update(statement, SqlBinder.none());
            }
            active.update(SQL_INSERT_VERSION, statement -> {
                statement.setString(1, migration.version());
                statement.setLong(2, Instant.now().toEpochMilli());
            });
            return null;
        });
        log.info("Applied database migration " + migration.version());
    }

    private List<String> appliedVersions(Database database) {
        return database.queryMany(SQL_SELECT_VERSIONS, SqlBinder.none(), row -> row.getString("version"));
    }

    private void requireUniqueVersions(List<Migration> migrations) {
        Set<String> seen = new HashSet<>();
        for (Migration migration : migrations) {
            if (!seen.add(migration.version())) {
                throw new StorageException("duplicate migration version: " + migration.version());
            }
        }
    }
}
