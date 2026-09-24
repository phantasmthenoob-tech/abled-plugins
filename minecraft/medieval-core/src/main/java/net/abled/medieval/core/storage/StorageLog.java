package net.abled.medieval.core.storage;

/**
 * Logging sink for the storage layer.
 *
 * <p>Exists so the core does not depend on a server logger or on a test framework: the Paper
 * module adapts the plugin logger, tests collect messages.
 */
public interface StorageLog {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable cause);

    /** Discards everything; only used by tests and by explicitly silent fallbacks. */
    static StorageLog noop() {
        return new StorageLog() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
            }

            @Override
            public void error(String message, Throwable cause) {
            }
        };
    }
}
