package net.abled.medieval.core.storage;

import java.util.List;
import java.util.Optional;

/**
 * Small, explicit SQL access layer. No ORM: repositories write their own statements and pass them
 * here with a binder and a row mapper.
 *
 * <h2>Thread ownership</h2>
 * Every method is safe to call from any thread, and implementations serialise access to their
 * underlying connection. Two consequences matter to callers:
 * <ul>
 *   <li>Point reads and single-row writes are cheap and may be performed on whichever thread
 *       already owns the work (for example the asynchronous pre-login thread).</li>
 *   <li>A {@link #transaction(SqlWork)} holds the connection for its whole duration, so nothing
 *       must be kept inside a transaction that is not a database write.</li>
 * </ul>
 * Bukkit entities, worlds and inventories must never be touched from a storage callback; results
 * are handed back to the main thread by the platform layer.
 */
public interface Database extends AutoCloseable {

    <T> Optional<T> queryOne(String sql, SqlBinder binder, RowMapper<T> mapper);

    <T> List<T> queryMany(String sql, SqlBinder binder, RowMapper<T> mapper);

    /** Executes an INSERT/UPDATE/DELETE and returns the number of affected rows. */
    int update(String sql, SqlBinder binder);

    /** Executes an INSERT and returns its generated key. */
    long insert(String sql, SqlBinder binder);

    /** Runs the work atomically: committed on success, rolled back on any failure. */
    <T> T transaction(SqlWork<T> work);

    /** True while the database can still be used; false after {@link #close()}. */
    boolean isOpen();

    @Override
    void close();
}
