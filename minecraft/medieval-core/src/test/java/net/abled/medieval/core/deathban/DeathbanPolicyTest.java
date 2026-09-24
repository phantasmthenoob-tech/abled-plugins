package net.abled.medieval.core.deathban;

import net.abled.medieval.core.config.MedievalSettings;
import net.abled.medieval.core.storage.StorageLog;
import net.abled.medieval.core.storage.StorageService;
import net.abled.medieval.core.storage.TestDatabases;
import net.abled.medieval.core.world.WorldStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deathban toggle and duration, tested against a real SQLite file: the point of the policy is
 * that a change survives a restart, which an in-memory stub could not show.
 */
class DeathbanPolicyTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private WorldStateRepository state;
    private MedievalSettings settings;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        storage = TestDatabases.open(tempDir.resolve("deathban-policy.db"));
        state = new WorldStateRepository(storage.database(), StorageLog.noop());
        settings = settingsFor(true, Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void followsConfigUntilSomethingIsSet() {
        DeathbanPolicy policy = load();

        assertTrue(policy.isEnabled());
        assertEquals(Duration.ofHours(1), policy.duration());
        assertFalse(policy.isOverridden());
        assertEquals(DeathbanPolicy.SOURCE_CONFIG, policy.source());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void switchingOffPersistsAcrossARestart() {
        DeathbanPolicy policy = load();
        assertTrue(policy.setEnabled(false, "tester"));

        assertEquals("false", state.get(DeathbanPolicy.KEY_ENABLED).orElseThrow());
        assertTrue(policy.isOverridden());
        assertEquals(DeathbanPolicy.SOURCE_RUNTIME, policy.source());

        DeathbanPolicy reloaded = new DeathbanPolicy(() -> settings, state, warnings::add);
        reloaded.load();
        assertFalse(reloaded.isEnabled());
        assertEquals(DeathbanPolicy.SOURCE_RUNTIME, reloaded.source());
    }

    @Test
    void reportsNoChangeInsteadOfWritingARow() {
        DeathbanPolicy policy = load();

        assertFalse(policy.setEnabled(true, "tester"), "config already had it on");
        assertFalse(policy.isOverridden(), "nothing to record, so the value keeps following config");
        assertTrue(state.get(DeathbanPolicy.KEY_ENABLED).isEmpty());

        assertTrue(policy.setEnabled(false, "tester"));
        assertFalse(policy.setEnabled(false, "tester"), "already off");
    }

    @Test
    void durationPersistsAndSurvivesARestart() {
        DeathbanPolicy policy = load();
        assertTrue(policy.setDuration(Duration.ofMinutes(30), "tester"));

        assertEquals("1800", state.get(DeathbanPolicy.KEY_DURATION_SECONDS).orElseThrow());

        DeathbanPolicy reloaded = new DeathbanPolicy(() -> settings, state, warnings::add);
        reloaded.load();
        assertEquals(Duration.ofMinutes(30), reloaded.duration());
        assertFalse(reloaded.setDuration(Duration.ofMinutes(30), "tester"), "already 30m");
    }

    @Test
    void rejectsNonPositiveDurations() {
        DeathbanPolicy policy = load();

        assertThrows(IllegalArgumentException.class, () -> policy.setDuration(Duration.ZERO, "tester"));
        assertThrows(IllegalArgumentException.class, () -> policy.setDuration(Duration.ofSeconds(-1), "tester"));
    }

    @Test
    void theTwoValuesAreOverriddenIndependently() {
        DeathbanPolicy policy = load();
        assertTrue(policy.setDuration(Duration.ofMinutes(15), "tester"));

        // A duration change must not freeze the enable flag: the toggle still works afterwards.
        assertTrue(policy.setEnabled(false, "tester"));
        assertFalse(policy.isEnabled());
        assertEquals(Duration.ofMinutes(15), policy.duration());
    }

    @Test
    void resetHandsBothValuesBackToConfig() {
        DeathbanPolicy policy = load();
        policy.setEnabled(false, "tester");
        policy.setDuration(Duration.ofDays(3), "tester");

        assertTrue(policy.reset());
        assertTrue(policy.isEnabled());
        assertEquals(Duration.ofHours(1), policy.duration());
        assertFalse(policy.isOverridden());
        assertFalse(policy.reset(), "nothing left to remove");
    }

    @Test
    void reloadKeepsAnOverrideButFollowsConfigForEverythingElse() {
        DeathbanPolicy policy = load();
        policy.setEnabled(false, "tester");
        policy.setDuration(Duration.ofMinutes(45), "tester");
        policy.reset();
        policy.setEnabled(false, "tester");

        // The owner edits config.yml and reloads.
        settings = settingsFor(true, Duration.ofHours(2));
        policy.applyConfigDefaults();

        assertFalse(policy.isEnabled(), "an overridden value keeps the stored decision");
        assertEquals(Duration.ofHours(2), policy.duration(), "the untouched value follows the file");
    }

    @Test
    void malformedStoredValuesFallBackToConfigAndAreReported() {
        state.put(DeathbanPolicy.KEY_DURATION_SECONDS, "soon");
        DeathbanPolicy policy = load();

        assertEquals(Duration.ofHours(1), policy.duration());
        assertFalse(policy.isOverridden());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("soon"), warnings.getFirst());
    }

    @Test
    void nonPositiveStoredDurationsAreIgnored() {
        state.put(DeathbanPolicy.KEY_DURATION_SECONDS, "0");
        DeathbanPolicy policy = load();

        assertEquals(Duration.ofHours(1), policy.duration());
        assertEquals(1, warnings.size());
    }

    @Test
    void applyReplacesOnlyTheDeathbanSection() {
        DeathbanPolicy policy = load();
        policy.setDuration(Duration.ofMinutes(20), "tester");

        MedievalSettings merged = policy.apply(settings);

        assertEquals(Duration.ofMinutes(20), merged.deathban().duration());
        assertSame(settings.dimensions(), merged.dimensions());
        assertSame(settings.siege(), merged.siege());
        assertSame(settings.territory(), merged.territory());
        assertSame(settings.land(), merged.land(), "a section this class knows nothing about must survive");
        assertSame(settings.admin(), merged.admin());
        assertTrue(merged.deathban().enabled());
    }

    private DeathbanPolicy load() {
        DeathbanPolicy policy = new DeathbanPolicy(() -> settings, state, warnings::add);
        policy.load();
        return policy;
    }

    private static MedievalSettings settingsFor(boolean deathbanEnabled, Duration duration) {
        return new MedievalSettings(
                new MedievalSettings.Deathban(deathbanEnabled, duration),
                new MedievalSettings.Dimensions(false, false),
                new MedievalSettings.Siege(true,
                        new MedievalSettings.Siege.Machine(1000, 100, Duration.ofSeconds(4)),
                        new MedievalSettings.Siege.Machine(750, 150, Duration.ofSeconds(6))),
                new MedievalSettings.Territory(25, true),
                new MedievalSettings.Admin("Tester"));
    }
}
