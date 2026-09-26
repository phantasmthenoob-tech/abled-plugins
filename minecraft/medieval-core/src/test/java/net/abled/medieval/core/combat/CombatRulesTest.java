package net.abled.medieval.core.combat;

import net.abled.medieval.core.config.MedievalSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatRulesTest {

    private static final MedievalSettings.Combat ON = MedievalSettings.Combat.defaults();
    private static final MedievalSettings.Combat OFF = new MedievalSettings.Combat(false, false, false, false);

    // ------------------------------------------------------------------
    // Quick charge on melee weapons
    // ------------------------------------------------------------------

    @Test
    void levelOneAttacksAboutAQuarterSooner() {
        assertEquals(0.90, CombatRules.meleeSpeedMultiplier(ON, 1).orElseThrow(), 1e-9);
    }

    @Test
    void levelFiveAttacksAboutHalfAsOftenAgain() {
        assertEquals(0.50, CombatRules.meleeSpeedMultiplier(ON, 5).orElseThrow(), 1e-9);
    }

    @Test
    void theReductionIsCappedSoTheWeaponNeverBecomesAMachineGun() {
        // Ten levels from the catalogue must not reach zero or go negative.
        double multiplier = CombatRules.meleeSpeedMultiplier(ON, 10).orElseThrow();
        assertTrue(multiplier >= 0.1, "multiplier " + multiplier + " fell below the floor");
        assertEquals(0.1, multiplier, 1e-9);
    }

    @Test
    void aWeaponWithoutTheEnchantmentKeepsItsVanillaSpeed() {
        assertTrue(CombatRules.meleeSpeedMultiplier(ON, 0).isEmpty());
    }

    @Test
    void theRuleOffMeansNoMultiplierAtAnyLevel() {
        assertTrue(CombatRules.meleeSpeedMultiplier(OFF, 5).isEmpty());
    }

    // ------------------------------------------------------------------
    // Piercing through shields
    // ------------------------------------------------------------------

    @Test
    void everyMeleeWeaponFamilyPierces() {
        assertTrue(CombatRules.piercesShields(ON, "diamond_sword"));
        assertTrue(CombatRules.piercesShields(ON, "iron_axe"));
        assertTrue(CombatRules.piercesShields(ON, "mace"));
        assertTrue(CombatRules.piercesShields(ON, "trident"));
    }

    @Test
    void aFistOrABowPiercesNothing() {
        assertFalse(CombatRules.piercesShields(ON, "air"));
        assertFalse(CombatRules.piercesShields(ON, "bow"));
        assertFalse(CombatRules.piercesShields(ON, "crossbow"));
        assertFalse(CombatRules.piercesShields(ON, "fishing_rod"));
    }

    @Test
    void theRuleOffLeavesEveryShieldStanding() {
        assertFalse(CombatRules.piercesShields(OFF, "diamond_sword"));
    }

    // ------------------------------------------------------------------
    // Infinity on consumables
    // ------------------------------------------------------------------

    @Test
    void theNamedConsumablesAreKept() {
        assertTrue(CombatRules.keepsConsumable(ON, "golden_apple"));
        assertTrue(CombatRules.keepsConsumable(ON, "enchanted_golden_apple"));
        assertTrue(CombatRules.keepsConsumable(ON, "cooked_beef"));
        assertTrue(CombatRules.keepsConsumable(ON, "mushroom_stew"));
        assertTrue(CombatRules.keepsConsumable(ON, "milk_bucket"));
    }

    @Test
    void aSwordIsNotAConsumable() {
        assertFalse(CombatRules.keepsConsumable(ON, "diamond_sword"));
    }

    @Test
    void theRuleOffConsumesEverything() {
        assertFalse(CombatRules.keepsConsumable(OFF, "golden_apple"));
        assertFalse(CombatRules.keepsThrown(OFF, "wind_charge"));
    }

    @Test
    void everyPotionSharesOneKeySoTheBottleIsWhatIsKept() {
        assertTrue(CombatRules.keepsConsumable(ON, "potion"));
        assertTrue(CombatRules.keepsConsumable(ON, "splash_potion"));
        assertTrue(CombatRules.keepsConsumable(ON, "lingering_potion"));
    }

    @Test
    void thrownConsumablesFollowTheSameRuleAsEatenOnes() {
        assertTrue(CombatRules.keepsThrown(ON, "wind_charge"));
        assertTrue(CombatRules.keepsThrown(ON, "ender_pearl"));
        assertFalse(CombatRules.keepsThrown(ON, "snowball"));
    }

    // ------------------------------------------------------------------
    // Infinity on the popping totem
    // ------------------------------------------------------------------

    @Test
    void aTotemWithInfinityComesBack() {
        assertTrue(CombatRules.keepsTotem(ON, "totem_of_undying"));
    }

    @Test
    void theTotemSwitchIsIndependentOfTheConsumableSwitch() {
        MedievalSettings.Combat totemOnly = new MedievalSettings.Combat(false, false, false, true);
        MedievalSettings.Combat neverTotem = new MedievalSettings.Combat(true, true, true, false);

        assertTrue(CombatRules.keepsTotem(totemOnly, "totem_of_undying"));
        assertFalse(CombatRules.keepsConsumable(totemOnly, "golden_apple"));
        assertFalse(CombatRules.keepsTotem(neverTotem, "totem_of_undying"));
    }

    @Test
    void onlyTheTotemQualifiesForTheTotemRule() {
        assertFalse(CombatRules.keepsTotem(ON, "golden_apple"));
        assertFalse(CombatRules.keepsTotem(ON, null));
    }
}
