package net.abled.medieval.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumableTest {

    @Test
    void theBattleFoodsAreConsumables() {
        assertTrue(Consumable.isKeyPath("golden_apple"));
        assertTrue(Consumable.isKeyPath("enchanted_golden_apple"));
        assertTrue(Consumable.isKeyPath("cooked_beef"));
        assertTrue(Consumable.isKeyPath("bread"));
    }

    @Test
    void theThrownChargesAreConsumables() {
        assertTrue(Consumable.isKeyPath("wind_charge"));
        assertTrue(Consumable.isKeyPath("ender_pearl"));
    }

    @Test
    void theRationsAndTheTreatsAreConsumables() {
        assertTrue(Consumable.isKeyPath("mushroom_stew"));
        assertTrue(Consumable.isKeyPath("rabbit_stew"));
        assertTrue(Consumable.isKeyPath("milk_bucket"));
        assertTrue(Consumable.isKeyPath("honey_bottle"));
        assertTrue(Consumable.isKeyPath("chorus_fruit"));
        assertTrue(Consumable.isKeyPath("cooked_salmon"));
        assertTrue(Consumable.isKeyPath("pumpkin_pie"));
    }

    @Test
    void theTotemIsAConsumable() {
        assertTrue(Consumable.isKeyPath("totem_of_undying"));
    }

    @Test
    void everythingElseIsNot() {
        assertFalse(Consumable.isKeyPath("shield"));
        assertFalse(Consumable.isKeyPath("diamond_sword"));
        assertFalse(Consumable.isKeyPath("totem_of_undying_v2"));
        // A prefix match is not a match: "cooked_beef" must not admit "cooked_beefsteak".
        assertFalse(Consumable.isKeyPath("cooked_beefsteak"));
        assertFalse(Consumable.isKeyPath(""));
        assertFalse(Consumable.isKeyPath(null));
    }

    @Test
    void matchingIgnoresCase() {
        assertTrue(Consumable.isKeyPath("Golden_Apple"));
    }
}
