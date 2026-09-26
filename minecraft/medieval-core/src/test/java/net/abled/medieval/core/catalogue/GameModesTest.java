package net.abled.medieval.core.catalogue;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameModesTest {

    @Test
    void theNumbersOneTwoThreeMeanSurvivalCreativeSpectator() {
        assertEquals(Optional.of(GameModes.Mode.SURVIVAL), GameModes.parse("1"));
        assertEquals(Optional.of(GameModes.Mode.CREATIVE), GameModes.parse("2"));
        assertEquals(Optional.of(GameModes.Mode.SPECTATOR), GameModes.parse("3"));
    }

    @Test
    void theNamesWorkInAnyCase() {
        assertEquals(Optional.of(GameModes.Mode.SURVIVAL), GameModes.parse("survival"));
        assertEquals(Optional.of(GameModes.Mode.CREATIVE), GameModes.parse("CREATIVE"));
        assertEquals(Optional.of(GameModes.Mode.SPECTATOR), GameModes.parse("  Spectator  "));
    }

    @Test
    void theShortAliasesWork() {
        assertEquals(Optional.of(GameModes.Mode.SURVIVAL), GameModes.parse("s"));
        assertEquals(Optional.of(GameModes.Mode.CREATIVE), GameModes.parse("c"));
        assertEquals(Optional.of(GameModes.Mode.SPECTATOR), GameModes.parse("sp"));
    }

    @Test
    void everythingElseIsRefused() {
        assertTrue(GameModes.parse("0").isEmpty());
        assertTrue(GameModes.parse("4").isEmpty());
        assertTrue(GameModes.parse("adventure").isEmpty());
        assertTrue(GameModes.parse("").isEmpty());
        assertTrue(GameModes.parse("twelve").isEmpty());
        assertTrue(GameModes.parse(null).isEmpty());
    }
}
