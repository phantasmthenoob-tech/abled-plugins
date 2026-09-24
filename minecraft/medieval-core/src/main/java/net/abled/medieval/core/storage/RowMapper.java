package net.abled.medieval.core.storage;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the current row of a result set to a domain object. */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet row) throws SQLException;
}
