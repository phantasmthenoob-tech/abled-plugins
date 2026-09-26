package net.abled.medieval.core.cmdblock;

import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link SqlWandStore} against a real SQLite file, exactly as the plugin wires it.
 *
 * <p>This is the regression test for the live "unexpected error": the first binding on an existing
 * server failed because the table creation was never executed. Every test here runs on a fresh
 * database through the shipped migrations - the same shape as an upgrade - so a store that cannot
 * create or use its table fails here and not in front of a player.
 */
class SqlWandStoreTest {

    @Test
    void theFirstBindingOnAFreshDatabaseWorks(@TempDir Path dir) {
        StorageService storage = TestDatabases.open(dir.resolve("medieval.db"));

        SqlWandStore store = new SqlWandStore(storage.database(), TestDatabases.logSilently());
        store.load();

        UUID id = UUID.randomUUID();
        WandBinding saved = store.save(id, WandBinding.clickWand(WandMode.Mode.IMPULSE, "give Notch diamond 1"));

        assertTrue(store.find(id).isPresent(), "the wand must be readable right after saving");
        assertEquals("give Notch diamond 1", saved.command());
        assertEquals(WandMode.Mode.IMPULSE, store.find(id).orElseThrow().mode());
    }

    @Test
    void aBindingSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("medieval.db");
        UUID id = UUID.randomUUID();

        StorageService first = TestDatabases.open(file);
        SqlWandStore store = new SqlWandStore(first.database(), TestDatabases.logSilently());
        store.save(id, new WandBinding(WandMode.Mode.REPEATING, WandTrigger.Trigger.ALWAYS, "time set day"));
        first.close();

        // A restart: new store over the same file, loading what the last one wrote.
        StorageService second = TestDatabases.open(file);
        SqlWandStore reloaded = new SqlWandStore(second.database(), TestDatabases.logSilently());
        reloaded.load();

        assertTrue(reloaded.find(id).isPresent(), "the binding must survive a restart");
        WandBinding found = reloaded.find(id).orElseThrow();
        assertEquals(WandMode.Mode.REPEATING, found.mode());
        assertEquals(WandTrigger.Trigger.ALWAYS, found.trigger());
        assertEquals("time set day", found.command());
    }

    @Test
    void anAlwaysActiveBindingSurvivesARestartToo(@TempDir Path dir) {
        // The repeating toggle is session-only by design, but the always-active trigger is stored -
        // this pins that difference, because a regression here would silently disarm every
        // always-active wand across a restart.
        Path file = dir.resolve("medieval.db");
        UUID id = UUID.randomUUID();

        StorageService first = TestDatabases.open(file);
        new SqlWandStore(first.database(), TestDatabases.logSilently())
                .save(id, new WandBinding(WandMode.Mode.IMPULSE, WandTrigger.Trigger.ALWAYS, "say tick"));
        first.close();

        StorageService second = TestDatabases.open(file);
        SqlWandStore reloaded = new SqlWandStore(second.database(), TestDatabases.logSilently());
        reloaded.load();

        assertEquals(WandTrigger.Trigger.ALWAYS, reloaded.find(id).orElseThrow().trigger());
    }

    @Test
    void anUnreadableRowIsSkippedNotFatal(@TempDir Path dir) {
        Path file = dir.resolve("medieval.db");

        StorageService first = TestDatabases.open(file);
        SqlWandStore writer = new SqlWandStore(first.database(), TestDatabases.logSilently());
        UUID good = UUID.randomUUID();
        writer.save(good, WandBinding.clickWand(WandMode.Mode.CHAIN, "say hello"));
        // A row the current parser cannot read, written with raw SQL.
        first.database().update("INSERT INTO cmdblock_wands (id, mode, trigger, command) VALUES (?, ?, ?, ?)",
                statement -> {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, "NOT_A_MODE");
                    statement.setString(3, "CLICK");
                    statement.setString(4, "say what");
                });
        first.close();

        StorageService second = TestDatabases.open(file);
        TestDatabases.CollectingLog log = new TestDatabases.CollectingLog();
        SqlWandStore reloaded = new SqlWandStore(second.database(), log);
        reloaded.load();

        assertTrue(reloaded.find(good).isPresent(), "the readable row must still load");
        assertTrue(log.errors().stream().anyMatch(message -> message.contains("unreadable")),
                "the bad row must be reported, not swallowed");
    }

    @Test
    void theStoreStartsEveryNewWandOnNeedsRedstone() {
        WandBinding binding = WandBinding.clickWand(WandMode.Mode.IMPULSE, "say hi");
        assertEquals(WandTrigger.Trigger.CLICK, binding.trigger());
    }
}
