package net.abled.medieval.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JDBC {@link Database} that owns a single lazily opened connection.
 *
 * <p>Design notes:
 * <ul>
 *   <li>One connection, guarded by a reentrant lock. A single connection removes the whole class
 *       of "database is locked" problems that SQLite otherwise produces under concurrent writers,
 *       and the workload here is small point reads and writes.</li>
 *   <li>Queries issued from inside a transaction reuse the same connection and lock, so repository
 *       code can compose.</li>
 *   <li>Transactions are not reentrant: starting one inside another is a programming error and
 *       fails loudly rather than silently committing early.</li>
 *   <li>A connection that has been closed underneath us (for example by a database file being
 *       replaced) is transparently reopened on the next operation.</li>
 * </ul>
 */
public final class JdbcDatabase implements Database {

    private final ConnectionFactory connectionFactory;
    private final StorageLog log;
    private final ReentrantLock lock = new ReentrantLock();
    private final ThreadLocal<Boolean> transactionActive = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Connection connection;
    private boolean closed;

    public JdbcDatabase(ConnectionFactory connectionFactory, StorageLog log) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public <T> Optional<T> queryOne(String sql, SqlBinder binder, RowMapper<T> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        lock.lock();
        try {
            try (PreparedStatement statement = prepare(sql, binder, false);
                 ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapper.map(rows)) : Optional.empty();
            } catch (SQLException failure) {
                throw failure("query", sql, failure);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> List<T> queryMany(String sql, SqlBinder binder, RowMapper<T> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        lock.lock();
        try {
            try (PreparedStatement statement = prepare(sql, binder, false);
                 ResultSet rows = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                while (rows.next()) {
                    results.add(mapper.map(rows));
                }
                return results;
            } catch (SQLException failure) {
                throw failure("query", sql, failure);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int update(String sql, SqlBinder binder) {
        lock.lock();
        try {
            try (PreparedStatement statement = prepare(sql, binder, false)) {
                return statement.executeUpdate();
            } catch (SQLException failure) {
                throw failure("update", sql, failure);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long insert(String sql, SqlBinder binder) {
        lock.lock();
        try {
            try (PreparedStatement statement = prepare(sql, binder, true)) {
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new StorageException("insert did not return a generated key: " + summarise(sql));
                    }
                    return keys.getLong(1);
                }
            } catch (SQLException failure) {
                throw failure("insert", sql, failure);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> T transaction(SqlWork<T> work) {
        Objects.requireNonNull(work, "work");
        lock.lock();
        try {
            if (Boolean.TRUE.equals(transactionActive.get())) {
                throw new StorageException("nested transactions are not supported");
            }

            Connection active = connection();
            boolean previousAutoCommit = active.getAutoCommit();
            active.setAutoCommit(false);
            transactionActive.set(Boolean.TRUE);
            try {
                T result = work.apply(this);
                active.commit();
                return result;
            } catch (SQLException failure) {
                rollbackQuietly(active);
                throw new StorageException("transaction failed and was rolled back", failure);
            } catch (RuntimeException failure) {
                rollbackQuietly(active);
                throw failure;
            } finally {
                transactionActive.set(Boolean.FALSE);
                restoreAutoCommit(active, previousAutoCommit);
            }
        } catch (SQLException failure) {
            throw new StorageException("could not start a transaction", failure);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isOpen() {
        lock.lock();
        try {
            return !closed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            Connection current = connection;
            connection = null;
            if (current != null) {
                try {
                    current.close();
                } catch (SQLException failure) {
                    log.error("Could not close the database connection", failure);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private PreparedStatement prepare(String sql, SqlBinder binder, boolean returnGeneratedKeys) throws SQLException {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(binder, "binder");

        PreparedStatement statement = returnGeneratedKeys
                ? connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                : connection().prepareStatement(sql);
        try {
            binder.bind(statement);
        } catch (SQLException | RuntimeException failure) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return statement;
    }

    private Connection connection() throws SQLException {
        if (closed) {
            throw new StorageException("the database has been closed");
        }
        if (connection == null || connection.isClosed()) {
            connection = connectionFactory.open();
        }
        return connection;
    }

    private void rollbackQuietly(Connection active) {
        try {
            active.rollback();
        } catch (SQLException failure) {
            log.error("Rolling back a failed transaction also failed", failure);
        }
    }

    private void restoreAutoCommit(Connection active, boolean previousAutoCommit) {
        try {
            active.setAutoCommit(previousAutoCommit);
        } catch (SQLException failure) {
            log.error("Could not restore auto-commit on the shared connection", failure);
        }
    }

    private StorageException failure(String operation, String sql, SQLException cause) {
        return new StorageException(operation + " failed: " + summarise(sql), cause);
    }

    private static String summarise(String sql) {
        String collapsed = sql.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 140 ? collapsed : collapsed.substring(0, 137) + "...";
    }
}
