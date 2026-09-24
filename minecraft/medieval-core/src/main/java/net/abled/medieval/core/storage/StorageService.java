package net.abled.medieval.core.storage;

import java.util.List;

/**
 * Lifecycle of the persisted game state.
 *
 * <p>{@link #open()} connects and brings the schema up to date. If it throws, startup must fail:
 * the plugin is not allowed to fall back to memory, because a "persistent" game system that
 * silently keeps everything in RAM loses kingdoms, claims and bans on the next restart.
 *
 * <h2>Thread ownership</h2>
 * {@link #open()} and {@link #close()} belong to the server's startup and shutdown thread. The
 * {@link Database} they expose is safe from any thread (see {@link Database} for the contract);
 * the platform layer decides which operations run off the tick thread and hands results back to
 * the main thread before touching server state.
 */
public interface StorageService extends AutoCloseable {

    void open();

    boolean isOpen();

    /** Only valid after {@link #open()}. */
    Database database();

    /** Schema versions recorded in the database, oldest first. */
    List<String> appliedMigrations();

    @Override
    void close();
}
