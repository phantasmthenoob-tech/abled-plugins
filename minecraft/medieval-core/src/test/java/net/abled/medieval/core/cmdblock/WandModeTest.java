package net.abled.medieval.core.cmdblock;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WandModeTest {

    @Test
    void numbersMapToTheOrderTheCommandPresents() {
        assertEquals(Optional.of(WandMode.Mode.IMPULSE), WandMode.parse("1"));
        assertEquals(Optional.of(WandMode.Mode.REPEATING), WandMode.parse("2"));
        assertEquals(Optional.of(WandMode.Mode.CHAIN), WandMode.parse("3"));
    }

    @Test
    void fullNamesParseCaseInsensitively() {
        assertEquals(Optional.of(WandMode.Mode.IMPULSE), WandMode.parse("Impulse"));
        assertEquals(Optional.of(WandMode.Mode.REPEATING), WandMode.parse("REPEATING"));
        assertEquals(Optional.of(WandMode.Mode.CHAIN), WandMode.parse("chain"));
    }

    @Test
    void shortAliasesAndTheBritishSpellingParse() {
        assertEquals(Optional.of(WandMode.Mode.IMPULSE), WandMode.parse("i"));
        assertEquals(Optional.of(WandMode.Mode.IMPULSE), WandMode.parse("normal"));
        assertEquals(Optional.of(WandMode.Mode.REPEATING), WandMode.parse("r"));
        assertEquals(Optional.of(WandMode.Mode.REPEATING), WandMode.parse("repeat"));
        assertEquals(Optional.of(WandMode.Mode.CHAIN), WandMode.parse("c"));
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertEquals(Optional.of(WandMode.Mode.IMPULSE), WandMode.parse("  impulse "));
    }

    @Test
    void anythingElseIsRefused() {
        assertTrue(WandMode.parse("4").isEmpty());
        assertTrue(WandMode.parse("0").isEmpty());
        assertTrue(WandMode.parse("creative").isEmpty());
        assertTrue(WandMode.parse("").isEmpty());
        assertTrue(WandMode.parse(null).isEmpty());
    }

    @Test
    void impulseFiresEveryClickRegardlessOfToggle() {
        assertTrue(WandRules.firesOnClick(WandMode.Mode.IMPULSE, false));
        assertTrue(WandRules.firesOnClick(WandMode.Mode.IMPULSE, true));
    }

    @Test
    void aRepeatingWandsClickIsAToggleNotAFire() {
        assertFalse(WandRules.firesOnClick(WandMode.Mode.REPEATING, false));
        assertFalse(WandRules.firesOnClick(WandMode.Mode.REPEATING, true));
    }

    @Test
    void onlyRepeatingWandsTickAndOnlyWhenActive() {
        assertTrue(WandRules.ticksWhenActive(WandMode.Mode.REPEATING, true, WandTrigger.Trigger.CLICK));
        assertFalse(WandRules.ticksWhenActive(WandMode.Mode.REPEATING, false, WandTrigger.Trigger.CLICK));
        assertFalse(WandRules.ticksWhenActive(WandMode.Mode.IMPULSE, true, WandTrigger.Trigger.CLICK));
        assertFalse(WandRules.ticksWhenActive(WandMode.Mode.CHAIN, true, WandTrigger.Trigger.CLICK));
    }

    @Test
    void anAlwaysActiveWandTicksWhateverItsMode() {
        assertTrue(WandRules.ticksWhenActive(WandMode.Mode.IMPULSE, false, WandTrigger.Trigger.ALWAYS));
        assertTrue(WandRules.ticksWhenActive(WandMode.Mode.REPEATING, false, WandTrigger.Trigger.ALWAYS));
        assertTrue(WandRules.ticksWhenActive(WandMode.Mode.CHAIN, false, WandTrigger.Trigger.ALWAYS));
    }

    @Test
    void onlyClickWandsAnswerClicks() {
        assertTrue(WandRules.answersClicks(WandTrigger.Trigger.CLICK));
        assertFalse(WandRules.answersClicks(WandTrigger.Trigger.ALWAYS));
    }

    @Test
    void triggerWordsParseIncludingVanillaPhrasings() {
        assertEquals(Optional.of(WandTrigger.Trigger.CLICK), WandTrigger.parse("click"));
        assertEquals(Optional.of(WandTrigger.Trigger.CLICK), WandTrigger.parse("needs-redstone"));
        assertEquals(Optional.of(WandTrigger.Trigger.CLICK), WandTrigger.parse("Redstone"));
        assertEquals(Optional.of(WandTrigger.Trigger.ALWAYS), WandTrigger.parse("always"));
        assertEquals(Optional.of(WandTrigger.Trigger.ALWAYS), WandTrigger.parse("always-active"));
        assertEquals(Optional.of(WandTrigger.Trigger.ALWAYS), WandTrigger.parse("Active"));
        assertTrue(WandTrigger.parse("sometimes").isEmpty());
        assertTrue(WandTrigger.parse("").isEmpty());
    }

    @Test
    void onlyChainWandsListenForSignals() {
        assertTrue(WandRules.firesOnSignal(WandMode.Mode.CHAIN));
        assertFalse(WandRules.firesOnSignal(WandMode.Mode.IMPULSE));
        assertFalse(WandRules.firesOnSignal(WandMode.Mode.REPEATING));
    }
}
