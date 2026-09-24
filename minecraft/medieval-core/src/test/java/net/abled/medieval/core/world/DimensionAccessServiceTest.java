package net.abled.medieval.core.world;

import net.abled.medieval.core.config.MapSettingsSource;
import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.config.SettingsLoader;
import net.abled.medieval.core.event.SimpleEventBus;
import net.abled.medieval.core.storage.StorageLog;
import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real SQLite file, not a mock, so the persisted-state path is exercised exactly as
 * it is on the server: the whole point of the gate is that a decision survives a restart.
 */
class DimensionAccessServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private WorldStateRepository state;
    private SimpleEventBus events;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        storage = TestDatabases.open(tempDir.resolve("dimensions.db"));
        state = new WorldStateRepository(storage.database(), StorageLog.noop());
        events = new SimpleEventBus(failure -> {
        });
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void followsConfigurationUntilSomethingIsStored() {
        DimensionAccessService gate = gate(() -> settings(true, false));
        gate.load();

        assertTrue(gate.isOpen(Dimension.NETHER));
        assertFalse(gate.isOpen(Dimension.END));
        assertFalse(gate.isOverridden(Dimension.NETHER), "nothing has been stored yet");
        assertTrue(state.all().isEmpty(), "loading must not write rows it did not change");
    }

    @Test
    void persistsChangesSoTheyOutliveTheProcess() {
        DimensionAccessService gate = gate(() -> settings(false, false));
        gate.load();

        assertTrue(gate.setOpen(Dimension.NETHER, true, "tester"));

        assertTrue(gate.isOpen(Dimension.NETHER));
        assertTrue(gate.isOverridden(Dimension.NETHER));
        assertEquals("true", state.get(Dimension.NETHER.stateKey()).orElseThrow());
    }

    @Test
    void isIdempotentWhenTheGateIsAlreadyAsRequested() {
        DimensionAccessService gate = gate(() -> settings(false, false));
        gate.load();

        assertFalse(gate.setOpen(Dimension.END, false, "tester"), "already closed");
        assertFalse(state.get(Dimension.END.stateKey()).isPresent(), "no redundant write");
    }

    @Test
    void storedDecisionWinsOverConfigurationAfterAReopen() {
        Path file = tempDir.resolve("reopened.db");
        try (StorageService first = TestDatabases.open(file)) {
            DimensionAccessService gate = new DimensionAccessService(() -> settings(false, false),
                    new WorldStateRepository(first.database(), StorageLog.noop()), events, warnings::add);
            gate.load();
            assertTrue(gate.setOpen(Dimension.NETHER, true, "tester"));
        }

        try (StorageService reopened = TestDatabases.open(file)) {
            DimensionAccessService gate = new DimensionAccessService(() -> settings(false, false),
                    new WorldStateRepository(reopened.database(), StorageLog.noop()), events, warnings::add);
            gate.load();

            assertTrue(gate.isOpen(Dimension.NETHER), "config.yml still says closed; the stored row decides");
            assertTrue(gate.isOverridden(Dimension.NETHER));
        }
    }

    @Test
    void reloadMovesOnlyTheGatesNobodyOverrode() {
        // The service reads configuration through a supplier, so a reload is simulated by swapping
        // the snapshot the supplier hands out - exactly what /medieval reload does on the server.
        AtomicReference<MedievalSettings> current = new AtomicReference<>(settings(false, false));
        DimensionAccessService gate = gate(current::get);
        gate.load();
        gate.setOpen(Dimension.NETHER, true, "tester");

        current.set(settings(true, true));
        gate.applyConfigDefaults();

        assertTrue(gate.isOpen(Dimension.NETHER), "an explicit decision is not undone by a reload");
        assertTrue(gate.isOpen(Dimension.END), "an untouched gate follows the new configuration");
    }

    @Test
    void publishesAnEventWhenAGateChanges() {
        List<DimensionAccessChanged> seen = new ArrayList<>();
        events.subscribe(DimensionAccessChanged.class, seen::add);

        DimensionAccessService gate = gate(() -> settings(false, false));
        gate.load();
        gate.setOpen(Dimension.END, true, "tester");

        assertEquals(1, seen.size());
        DimensionAccessChanged change = seen.get(0);
        assertEquals(Dimension.END, change.dimension());
        assertTrue(change.open());
        assertEquals("tester", change.actor());
    }

    @Test
    void snapshotReportsEveryGate() {
        DimensionAccessService gate = gate(() -> settings(true, false));
        gate.load();

        Map<Dimension, Boolean> snapshot = gate.snapshot();
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.get(Dimension.NETHER));
        assertFalse(snapshot.get(Dimension.END));
    }

    @Test
    void unreadableStorageFallsBackToConfigurationAndWarns() {
        storage.close();

        DimensionAccessService gate = gate(() -> settings(true, false));
        gate.load();

        assertTrue(gate.isOpen(Dimension.NETHER));
        assertFalse(gate.isOpen(Dimension.END));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("config.yml"), warnings.get(0));
    }

    @Test
    void rejectsNullArguments() {
        DimensionAccessService gate = gate(() -> settings(false, false));

        assertThrows(NullPointerException.class, () -> gate.isOpen(null));
        assertThrows(NullPointerException.class, () -> gate.setOpen(null, true, "tester"));
        assertThrows(NullPointerException.class, () -> gate.setOpen(Dimension.END, true, null));
    }

    private DimensionAccessService gate(Supplier<MedievalSettings> settings) {
        return new DimensionAccessService(settings, state, events, warnings::add);
    }

    private static MedievalSettings settings(boolean nether, boolean end) {
        return SettingsLoader.load(new MapSettingsSource(Map.<String, Object>of(
                "dimensions.nether.enabled", nether,
                "dimensions.end.enabled", end)), warning -> {
        });
    }
}
