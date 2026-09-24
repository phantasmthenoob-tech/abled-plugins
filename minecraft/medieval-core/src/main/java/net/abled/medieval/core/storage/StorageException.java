package net.abled.medieval.core.storage;

/**
 * Raised when a storage operation fails or the store is used incorrectly.
 *
 * <p>SQL exceptions are never swallowed: they are wrapped here with the failing operation and the
 * offending SQL, so logs point at the actual statement.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
