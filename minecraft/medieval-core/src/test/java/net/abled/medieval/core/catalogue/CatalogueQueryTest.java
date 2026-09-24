package net.abled.medieval.core.catalogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueQueryTest {

    @Test
    void normalisesSeparatorsAndCase() {
        assertEquals("diamond sword", CatalogueQuery.normalize("DIAMOND_SWORD"));
        assertEquals("diamond sword", CatalogueQuery.normalize("diamond-sword"));
        assertEquals("diamond sword", CatalogueQuery.normalize("  Diamond   Sword  "));
        assertEquals("diamond sword", CatalogueQuery.normalize("diamond__sword"));
        assertEquals("", CatalogueQuery.normalize(null));
        assertEquals("", CatalogueQuery.normalize("   "));
        assertEquals("", CatalogueQuery.normalize("___"));
    }

    @Test
    void matchesAPartialId() {
        assertTrue(CatalogueQuery.matches("sword", "diamond_sword", "DIAMOND_SWORD"));
        assertTrue(CatalogueQuery.matches("amond", "diamond_sword"));
        assertTrue(CatalogueQuery.matches("command", "command_block", "COMMAND_BLOCK"));
    }

    @Test
    void matchesWhenEveryTermIsPresentRegardlessOfOrder() {
        assertTrue(CatalogueQuery.matches("diamond sword", "diamond_sword"));
        assertTrue(CatalogueQuery.matches("block command", "command_block"));
        assertTrue(CatalogueQuery.matches("block command", "COMMAND_BLOCK"));
    }

    @Test
    void requiresEveryTermToMatch() {
        assertFalse(CatalogueQuery.matches("diamond axe", "diamond_sword"),
                "a term that is absent must not be ignored");
    }

    @Test
    void ignoresQueryCaseAndSpacingStyle() {
        assertTrue(CatalogueQuery.matches("Diamond_Sword", "diamond_sword"));
        assertTrue(CatalogueQuery.matches("  diamond   sword ", "diamond_sword"));
    }

    @Test
    void blankQueryMatchesNothing() {
        assertFalse(CatalogueQuery.matches("", "diamond_sword"),
                "an empty search shows an empty result, not the whole registry");
        assertFalse(CatalogueQuery.matches("   ", "diamond_sword"));
        assertFalse(CatalogueQuery.matches(null, "diamond_sword"));
    }

    @Test
    void toleratesMissingCandidates() {
        assertFalse(CatalogueQuery.matches("sword"));
        assertFalse(CatalogueQuery.matches("sword", (String) null));
        assertFalse(CatalogueQuery.matches("sword", null, ""));
        assertTrue(CatalogueQuery.matches("sword", null, "iron_sword"));
    }
}
