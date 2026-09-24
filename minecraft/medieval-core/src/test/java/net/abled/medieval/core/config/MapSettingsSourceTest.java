package net.abled.medieval.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSettingsSourceTest {

    private static final MapSettingsSource SOURCE = new MapSettingsSource(Map.of(
            "boolean.value", true,
            "quoted.boolean", "false",
            "integer.value", 42,
            "long.value", 9_000_000_000L,
            "text.value", "medieval"));

    @Test
    void readsBooleans() {
        assertTrue(SOURCE.getBoolean("boolean.value", false));
        assertFalse(SOURCE.getBoolean("quoted.boolean", true));
    }

    @Test
    void fallsBackForMissingOrInvalidBooleans() {
        assertTrue(SOURCE.getBoolean("missing.path", true));
        assertTrue(SOURCE.getBoolean("text.value", true));
    }

    @Test
    void readsNumbers() {
        assertEquals(42, SOURCE.getInt("integer.value", 0));
        assertEquals(9_000_000_000L, SOURCE.getLong("long.value", 0L));
    }

    @Test
    void parsesNumericStrings() {
        MapSettingsSource source = new MapSettingsSource(Map.of("text.number", " 3600 "));
        assertEquals(3600, source.getInt("text.number", 0));
        assertEquals(3600L, source.getLong("text.number", 0L));
    }

    @Test
    void fallsBackForUnparseableNumbers() {
        assertEquals(7, SOURCE.getInt("text.value", 7));
        assertEquals(7L, SOURCE.getLong("text.value", 7L));
    }

    @Test
    void readsStringsAndPresence() {
        assertEquals("medieval", SOURCE.getString("text.value", "fallback"));
        assertEquals("fallback", SOURCE.getString("missing.path", "fallback"));
        assertTrue(SOURCE.has("boolean.value"));
        assertFalse(SOURCE.has("missing.path"));
    }

    @Test
    void emptySourceUsesFallbacks() {
        SettingsSource empty = MapSettingsSource.empty();
        assertFalse(empty.has("anything"));
        assertEquals(5, empty.getInt("anything", 5));
        assertFalse(empty.getBoolean("anything", false));
    }
}
