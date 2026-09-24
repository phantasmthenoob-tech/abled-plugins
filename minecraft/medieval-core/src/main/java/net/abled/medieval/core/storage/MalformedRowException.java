package net.abled.medieval.core.storage;

/** A row that could be read from the database but whose contents are not usable. */
public final class MalformedRowException extends StorageException {

    public MalformedRowException(String message) {
        super(message);
    }

    public MalformedRowException(String message, Throwable cause) {
        super(message, cause);
    }
}
