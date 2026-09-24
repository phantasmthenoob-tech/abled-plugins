package net.abled.medieval.paper.storage;

import net.abled.medieval.core.storage.StorageLog;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes storage logging into the plugin logger.
 *
 * <p>Database messages are prefixed so an administrator scanning the console can tell a schema or
 * statement problem from gameplay logging.
 */
public final class PaperStorageLog implements StorageLog {

    private static final String PREFIX = "[storage] ";

    private final Logger logger;

    public PaperStorageLog(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void info(String message) {
        logger.info(PREFIX + message);
    }

    @Override
    public void warn(String message) {
        logger.warning(PREFIX + message);
    }

    @Override
    public void error(String message, Throwable cause) {
        if (cause == null) {
            logger.severe(PREFIX + message);
        } else {
            logger.log(Level.SEVERE, PREFIX + message, cause);
        }
    }
}
