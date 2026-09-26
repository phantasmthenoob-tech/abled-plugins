package net.abled.medieval.paper.compat;

import net.abled.medieval.api.MedievalScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link MedievalScheduler} on Paper's scheduler.
 *
 * <p>This is the only class in the plugin that talks to a server scheduler, which is what makes the
 * threading rules stated by {@link MedievalScheduler} enforceable by reading one file. Scheduling
 * is also best-effort: during shutdown the scheduler refuses new tasks, and that refusal is logged
 * instead of being thrown into a caller that is only doing housekeeping.
 */
public final class PaperScheduler implements MedievalScheduler {

    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final Consumer<String> warnings;
    private final List<BukkitTask> repeating = new ArrayList<>();

    public PaperScheduler(Plugin plugin, Consumer<String> warnings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    @Override
    public void runAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, guard(task, "asynchronous task"));
        } catch (RuntimeException failure) {
            report("Could not schedule an asynchronous task", failure);
        }
    }

    @Override
    public void runSync(Runnable task) {
        Objects.requireNonNull(task, "task");
        Runnable guarded = guard(task, "synchronous task");
        if (Bukkit.isPrimaryThread()) {
            guarded.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, guarded);
        } catch (RuntimeException failure) {
            report("Could not schedule a task on the server thread", failure);
        }
    }

    @Override
    public void runSyncLater(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(delay, "delay");
        // No isPrimaryThread shortcut here: a caller that asks for "later" means later, even from
        // the tick thread - the whole point is to let the server finish the current action first.
        try {
            Bukkit.getScheduler().runTaskLater(plugin, guard(task, "delayed task"), toTicks(delay));
        } catch (RuntimeException failure) {
            report("Could not schedule a delayed task on the server thread", failure);
        }
    }

    @Override
    public void runAsyncRepeating(Runnable task, Duration initialDelay, Duration period) {
        Objects.requireNonNull(task, "task");
        try {
            BukkitTask scheduled = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin, guard(task, "repeating task"), toTicks(initialDelay), toTicks(period));
            synchronized (repeating) {
                repeating.add(scheduled);
            }
        } catch (RuntimeException failure) {
            report("Could not schedule a repeating task", failure);
        }
    }

    @Override
    public void runSyncRepeating(Runnable task, Duration initialDelay, Duration period) {
        Objects.requireNonNull(task, "task");
        try {
            // Registered in the same list as the asynchronous repeats so that one shutdown path cancels
            // both: a task still waiting for its next tick while storage is being closed is exactly the
            // race cancelRepeating exists to prevent.
            BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(
                    plugin, guard(task, "repeating tick task"), toTicks(initialDelay), toTicks(period));
            synchronized (repeating) {
                repeating.add(scheduled);
            }
        } catch (RuntimeException failure) {
            report("Could not schedule a repeating tick task", failure);
        }
    }

    @Override
    public void cancelRepeating() {
        List<BukkitTask> scheduled;
        synchronized (repeating) {
            scheduled = List.copyOf(repeating);
            repeating.clear();
        }
        for (BukkitTask task : scheduled) {
            try {
                task.cancel();
            } catch (RuntimeException failure) {
                report("Could not cancel a repeating task", failure);
            }
        }
    }

    /**
     * Wraps a task so a failure inside it is logged instead of silently killing the scheduler
     * thread, which is what an uncaught exception in a repeating task would do.
     */
    private Runnable guard(Runnable task, String description) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException failure) {
                report("A scheduled " + description + " failed", failure);
            }
        };
    }

    private void report(String message, RuntimeException failure) {
        warnings.accept(message + ": " + failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : " (" + failure.getMessage() + ")"));
    }

    private static long toTicks(Duration duration) {
        long ticks = duration.toMillis() / MILLIS_PER_TICK;
        return Math.max(ticks, 1L);
    }
}
