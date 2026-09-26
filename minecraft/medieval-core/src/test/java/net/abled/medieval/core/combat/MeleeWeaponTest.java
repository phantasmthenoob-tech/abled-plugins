package net.abled.medieval.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeleeWeaponTest {

    @Test
    void everyVanillaSwordTierIsASword() {
        assertTrue(MeleeWeapon.SWORD.matches("wooden_sword"));
        assertTrue(MeleeWeapon.SWORD.matches("stone_sword"));
        assertTrue(MeleeWeapon.SWORD.matches("iron_sword"));
        assertTrue(MeleeWeapon.SWORD.matches("golden_sword"));
        assertTrue(MeleeWeapon.SWORD.matches("diamond_sword"));
        assertTrue(MeleeWeapon.SWORD.matches("netherite_sword"));
    }

    @Test
    void everyVanillaAxeTierIsAnAxe() {
        assertTrue(MeleeWeapon.AXE.matches("iron_axe"));
        assertTrue(MeleeWeapon.AXE.matches("netherite_axe"));
    }

    @Test
    void theMaceAndTheTridentAreTheirOwnFamilies() {
        assertTrue(MeleeWeapon.MACE.matches("mace"));
        assertTrue(MeleeWeapon.TRIDENT.matches("trident"));
    }

    @Test
    void aNonWeaponIsNobody() {
        assertFalse(MeleeWeapon.SWORD.matches("shield"));
        assertFalse(MeleeWeapon.AXE.matches("pickaxe"));
        assertTrue(MeleeWeapon.of("stick").isEmpty());
        assertTrue(MeleeWeapon.of("bow").isEmpty());
    }

    @Test
    void aNullKeyIsNobody() {
        assertFalse(MeleeWeapon.SWORD.matches(null));
        assertTrue(MeleeWeapon.of(null).isEmpty());
    }

    @Test
    void matchingIgnoresCase() {
        assertTrue(MeleeWeapon.SWORD.matches("Iron_Sword"));
    }

    @Test
    void theFamilyIsFoundFromTheKey() {
        assertEquals(MeleeWeapon.MACE, MeleeWeapon.of("mace").orElseThrow());
        assertEquals(MeleeWeapon.TRIDENT, MeleeWeapon.of("trident").orElseThrow());
        assertEquals(MeleeWeapon.AXE, MeleeWeapon.of("golden_axe").orElseThrow());
    }
}
