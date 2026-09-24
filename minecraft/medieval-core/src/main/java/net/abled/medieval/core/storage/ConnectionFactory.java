package net.abled.medieval.core.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens JDBC connections. Implemented in the platform layer for SQLite so that core code never
 * depends on a specific database product.
 */
@FunctionalInterface
public interface ConnectionFactory {

    Connection open() throws SQLException;
}
