package net.abled.medieval.core.storage;

import java.util.List;
import java.util.Objects;

/** {@link StorageService} backed by a {@link ConnectionFactory} and a list of migrations. */
public final class JdbcStorageService implements StorageService {

    private final ConnectionFactory connectionFactory;
    private final List<Migration> migrations;
    private final StorageLog log;

    private JdbcDatabase database;
    private List<String> appliedMigrations = List.of();

    public JdbcStorageService(ConnectionFactory connectionFactory, List<Migration> migrations, StorageLog log) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public void open() {
        if (database != null) {
            return;
        }

        JdbcDatabase candidate = new JdbcDatabase(connectionFactory, log);
        try {
            List<String> applied = new SchemaMigrator(log).migrate(candidate, migrations);
            this.appliedMigrations = applied;
            this.database = candidate;
        } catch (RuntimeException failure) {
            // Never leave a half-initialised handle behind.
            candidate.close();
            throw failure;
        }
    }

    @Override
    public boolean isOpen() {
        JdbcDatabase current = database;
        return current != null && current.isOpen();
    }

    @Override
    public Database database() {
        JdbcDatabase current = database;
        if (current == null) {
            throw new StorageException("storage has not been opened yet");
        }
        return current;
    }

    @Override
    public List<String> appliedMigrations() {
        return List.copyOf(appliedMigrations);
    }

    @Override
    public void close() {
        JdbcDatabase current = database;
        database = null;
        if (current != null) {
            current.close();
        }
    }
}
