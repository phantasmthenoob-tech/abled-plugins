package net.abled.medieval.core.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Small helpers for reading columns safely.
 *
 * <p>Unreadable values raise {@link MalformedRowException} instead of silently becoming
 * {@code null} or zero, so a corrupted row is either skipped with a logged error or fails the
 * operation — never quietly changes gameplay data.
 */
public final class SqlRows {

    private SqlRows() {
    }

    public static UUID uuid(ResultSet row, String column) throws SQLException {
        String raw = row.getString(column);
        if (raw == null || raw.isBlank()) {
            throw new MalformedRowException("column '" + column + "' is missing a UUID");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException failure) {
            throw new MalformedRowException("column '" + column + "' is not a UUID: '" + raw + "'", failure);
        }
    }

    public static Instant instant(ResultSet row, String column) throws SQLException {
        long millis = row.getLong(column);
        if (row.wasNull()) {
            throw new MalformedRowException("column '" + column + "' is missing a timestamp");
        }
        return Instant.ofEpochMilli(millis);
    }

    public static String requiredText(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        if (value == null || value.isBlank()) {
            throw new MalformedRowException("column '" + column + "' is missing a value");
        }
        return value;
    }
}
