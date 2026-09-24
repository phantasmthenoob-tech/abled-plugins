package net.abled.medieval.core.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Opens connections through {@link DriverManager} and applies a list of session statements to each
 * new connection (pragmas, session settings).
 *
 * <p>The driver class is loaded explicitly through this class's classloader: when the JDBC driver
 * is supplied by the server (Paper's {@code libraries:} mechanism) explicit loading gives a clear
 * error message instead of an opaque "no suitable driver" failure.
 */
public final class DriverManagerConnectionFactory implements ConnectionFactory {

    private final String url;
    private final String driverClassName;
    private final List<String> sessionStatements;

    public DriverManagerConnectionFactory(String url, String driverClassName, List<String> sessionStatements) {
        this.url = Objects.requireNonNull(url, "url");
        this.driverClassName = Objects.requireNonNull(driverClassName, "driverClassName");
        this.sessionStatements = List.copyOf(Objects.requireNonNull(sessionStatements, "sessionStatements"));
    }

    @Override
    public Connection open() throws SQLException {
        loadDriver();

        Connection connection = DriverManager.getConnection(url);
        try {
            applySessionStatements(connection);
        } catch (SQLException failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return connection;
    }

    private void loadDriver() {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException failure) {
            throw new StorageException("JDBC driver '" + driverClassName + "' is not on the classpath; "
                    + "check the plugin's libraries entry", failure);
        }
    }

    private void applySessionStatements(Connection connection) throws SQLException {
        for (String statement : sessionStatements) {
            // execute() rather than executeUpdate(): pragmas such as journal_mode return a row.
            try (Statement session = connection.createStatement()) {
                session.execute(statement);
            }
        }
    }
}
