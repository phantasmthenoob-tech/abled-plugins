package net.abled.medieval.core.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsLoaderTest {

    private final List<String> warnings = new ArrayList<>();

    private MedievalSettings load(Map<String, Object> values) {
        return SettingsLoader.load(new MapSettingsSource(values), warnings::add);
    }

    @Test
    void appliesDocumentedDefaultsToAnEmptySource() {
        MedievalSettings settings = load(Map.of());

        assertTrue(settings.deathban().enabled());
        assertEquals(3600L, settings.deathban().duration().toSeconds());
        assertFalse(settings.dimensions().netherEnabled());
        assertFalse(settings.dimensions().endEnabled());
        assertTrue(settings.siege().enabled());
        assertEquals(1000, settings.siege().ram().health());
        assertEquals(100, settings.siege().ram().damage());
        assertEquals(4L, settings.siege().ram().cooldown().toSeconds());
        assertEquals(750, settings.siege().catapult().health());
        assertEquals(150, settings.siege().catapult().damage());
        assertEquals(6L, settings.siege().catapult().cooldown().toSeconds());
        assertEquals(25, settings.territory().maxClaimsPerKingdom());
        assertTrue(settings.territory().protectClaims());
        assertEquals(List.of(), warnings, "defaults must not warn");
    }

    @Test
    void appliesConfiguredValues() {
        // Map.ofEntries, not Map.of: this configuration has more than the ten key/value pairs that
        // Map.of accepts, and each value is widened to Object so all entries share one value type.
        MedievalSettings settings = load(Map.ofEntries(
                Map.entry("deathban.enabled", (Object) false),
                Map.entry("deathban.duration-seconds", 60L),
                Map.entry("dimensions.nether.enabled", true),
                Map.entry("dimensions.end.enabled", true),
                Map.entry("siege.enabled", false),
                Map.entry("siege.ram.health", 500),
                Map.entry("siege.ram.damage", 25),
                Map.entry("siege.ram.cooldown-seconds", 2L),
                Map.entry("siege.catapult.health", 300),
                Map.entry("siege.catapult.damage", 75),
                Map.entry("siege.catapult.cooldown-seconds", 10L),
                Map.entry("territory.max-claims-per-kingdom", 5),
                Map.entry("territory.protect-claims", false)));

        assertFalse(settings.deathban().enabled());
        assertEquals(60L, settings.deathban().duration().toSeconds());
        assertTrue(settings.dimensions().netherEnabled());
        assertTrue(settings.dimensions().endEnabled());
        assertFalse(settings.siege().enabled());
        assertEquals(500, settings.siege().ram().health());
        assertEquals(25, settings.siege().ram().damage());
        assertEquals(2L, settings.siege().ram().cooldown().toSeconds());
        assertEquals(300, settings.siege().catapult().health());
        assertEquals(75, settings.siege().catapult().damage());
        assertEquals(10L, settings.siege().catapult().cooldown().toSeconds());
        assertEquals(5, settings.territory().maxClaimsPerKingdom());
        assertFalse(settings.territory().protectClaims());
        assertEquals(List.of(), warnings);
    }

    @Test
    void rejectsZeroOrNegativeDeathbanDuration() {
        MedievalSettings settings = load(Map.of("deathban.duration-seconds", -5L));

        assertEquals(3600L, settings.deathban().duration().toSeconds());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("deathban.duration-seconds"), warnings.get(0));
    }

    @Test
    void rejectsNonPositiveMachineHealth() {
        MedievalSettings settings = load(Map.of("siege.ram.health", 0));

        assertEquals(1000, settings.siege().ram().health());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("siege.ram.health"), warnings.get(0));
    }

    @Test
    void rejectsNegativeMachineDamageAndCooldown() {
        MedievalSettings settings = load(Map.of(
                "siege.catapult.damage", -1,
                "siege.catapult.cooldown-seconds", -2L));

        assertEquals(150, settings.siege().catapult().damage());
        assertEquals(6L, settings.siege().catapult().cooldown().toSeconds());
        assertEquals(2, warnings.size());
    }

    @Test
    void rejectsNegativeClaimLimit() {
        MedievalSettings settings = load(Map.of("territory.max-claims-per-kingdom", -3));

        assertEquals(25, settings.territory().maxClaimsPerKingdom());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("territory.max-claims-per-kingdom"), warnings.get(0));
    }

    @Test
    void catalogueOwnerDefaultsToTheShippedName() {
        MedievalSettings settings = load(Map.of());

        assertEquals("Disgraced_", MedievalSettings.DEFAULT_SECRET_OWNER);
        assertEquals(MedievalSettings.DEFAULT_SECRET_OWNER, settings.admin().secretOwner());
        assertEquals(List.of(), warnings, "an absent owner entry is not a misconfiguration");
    }

    @Test
    void readsTheConfiguredCatalogueOwner() {
        MedievalSettings settings = load(Map.of("admin.secret-owner", "  SomeoneElse  "));

        assertEquals("SomeoneElse", settings.admin().secretOwner());
        assertEquals(List.of(), warnings);
    }

    @Test
    void blankCatalogueOwnerFallsBackAndWarns() {
        MedievalSettings settings = load(Map.of("admin.secret-owner", "   "));

        assertEquals(MedievalSettings.DEFAULT_SECRET_OWNER, settings.admin().secretOwner());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("admin.secret-owner"), warnings.get(0));
    }

    @Test
    void summaryReportsCurrentState() {
        String summary = load(Map.of()).summary();

        assertTrue(summary.contains("deathban=3600s"), summary);
        assertTrue(summary.contains("nether=closed"), summary);
        assertTrue(summary.contains("end=closed"), summary);
        assertTrue(summary.contains("claims=25"), summary);
        // The owner line goes to the console only, never to a player-facing message.
        assertTrue(summary.contains("owner=" + MedievalSettings.DEFAULT_SECRET_OWNER), summary);
    }

    @Test
    void modelRejectsNegativeValuesDirectly() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MedievalSettings.Territory(-1, true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MedievalSettings.Siege.Machine(0, 1, java.time.Duration.ofSeconds(1)));
    }
}
