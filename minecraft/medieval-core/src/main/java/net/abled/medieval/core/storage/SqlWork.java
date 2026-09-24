package net.abled.medieval.core.storage;

import java.sql.SQLException;

/** A unit of work executed inside a database transaction. */
@FunctionalInterface
public interface SqlWork<T> {

    T apply(Database database) throws SQLException;
}
