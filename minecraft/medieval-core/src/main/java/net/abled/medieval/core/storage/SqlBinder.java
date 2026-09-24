package net.abled.medieval.core.storage;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Binds parameters onto a prepared statement. */
@FunctionalInterface
public interface SqlBinder {

    void bind(PreparedStatement statement) throws SQLException;

    /** Binder for statements without parameters. */
    static SqlBinder none() {
        return statement -> {
        };
    }
}
