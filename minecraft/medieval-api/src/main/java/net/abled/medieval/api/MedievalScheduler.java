package net.abled.medieval.api;

import java.time.Duration;

/**
 * The host server's task scheduling, reduced to the operations core-facing code needs.
 *
 * <p>This is part of the compatibility layer: everything that has to run off the tick thread (or
 * has to be handed back to it) goes through here, so no other class calls a server scheduler
 * directly and a different platform can implement the same contract.
 *
 * <h2>Threading contract</h2>
 * <ul>
 *   <li>{@link #runAsync(Runnable)} runs the task off the server tick thread. Server state
 *       (players, worlds, inventories, entities) must never be touched from it, and a failure
 *       inside the task must be handled by the task itself - it cannot propagate to the server.</li>
 *   <li>{@link #runSync(Runnable)} runs the task on the tick thread, so server state may be
 *       touched. By the time a database result reaches a {@code runSync} callback, the result must
 *       be an immutable snapshot: it is not safe to hand a live JDBC result set across threads.</li>
 *   <li>{@link #runAsyncRepeating(Runnable, Duration, Duration)} schedules background housekeeping.
 *       Repeating tasks are cancelled by {@link #cancelRepeating()}, which the platform calls
 *       before it closes storage during shutdown so no task can race the shutdown.</li>
 *   <li>{@link #runSyncRepeating(Runnable, Duration, Duration)} schedules work that has to happen on
 *       the tick thread every tick or every few ticks - driving an incremental search, for example.
 *       It is cancelled by the same {@link #cancelRepeating()}.</li>
 * </ul>
 */
public interface MedievalScheduler {

    /** Runs the task once, off the tick thread. */
    void runAsync(Runnable task);

    /**
     * Runs the task on the tick thread: immediately when the caller already is the tick thread,
     * otherwise on the next tick.
     */
    void runSync(Runnable task);

    /**
     * Runs the task once on the tick thread, after the given delay.
     *
     * <p>For work that must wait for the server to settle: handing a totem back after it has
     * actually been spent, for example, rather than the instant the resurrect event fires. Like
     * {@link #runSync(Runnable)}, the task may touch server state; a caller that already is the
     * tick thread must still not be served its callback early, so an immediate zero-delay request
     * waits for the next tick like any other.
     */
    void runSyncLater(Runnable task, Duration delay);

    /** Runs the task repeatedly, off the tick thread. */
    void runAsyncRepeating(Runnable task, Duration initialDelay, Duration period);

    /**
     * Runs the task repeatedly on the tick thread.
     *
     * <p>For work that must be spread over ticks rather than done at once: a task that reads or
     * changes server state in small slices, where running the whole thing would show up as a tick
     * spike. The task must be small enough to finish well inside its period - the scheduler runs it
     * whatever state the server is in, and a slow one delays the tick it was meant to fit into.
     */
    void runSyncRepeating(Runnable task, Duration initialDelay, Duration period);

    /** Cancels every repeating task started through this scheduler, synchronous or not. */
    void cancelRepeating();
}
