package net.abled.medieval.paper.catalogue;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelsTest {

    @Test
    void parsesDigits() {
        assertEquals(Optional.of(1), Levels.parse("1", 5));
        assertEquals(Optional.of(3), Levels.parse("3", 5));
        assertEquals(Optional.of(10), Levels.parse("10", 10));
    }

    @Test
    void parsesRomanNumeralsCaseInsensitively() {
        assertEquals(Optional.of(1), Levels.parse("I", 5));
        assertEquals(Optional.of(2), Levels.parse("ii", 5));
        assertEquals(Optional.of(3), Levels.parse("III", 5));
        assertEquals(Optional.of(4), Levels.parse("iv", 5));
        assertEquals(Optional.of(5), Levels.parse("V", 5));
        assertEquals(Optional.of(10), Levels.parse("x", 10));
    }

    @Test
    void parsesNumberWords() {
        assertEquals(Optional.of(1), Levels.parse("one", 5));
        assertEquals(Optional.of(2), Levels.parse("TWO", 5));
        assertEquals(Optional.of(5), Levels.parse("five", 5));
        assertEquals(Optional.of(10), Levels.parse("Ten", 10));
    }

    @Test
    void clampsToTheEnchantmentMaximum() {
        assertEquals(Optional.of(5), Levels.parse("99", 5));
        assertEquals(Optional.of(1), Levels.parse("1000", 1));
        assertEquals(Optional.of(3), Levels.parse("five", 3));
    }

    @Test
    void rejectsZeroAndJunk() {
        assertEquals(Optional.empty(), Levels.parse("0", 5));
        assertEquals(Optional.empty(), Levels.parse("", 5));
        assertEquals(Optional.empty(), Levels.parse(null, 5));
        assertEquals(Optional.empty(), Levels.parse("-1", 5));
        assertEquals(Optional.empty(), Levels.parse("2 5", 5));
        assertEquals(Optional.empty(), Levels.parse("3.0", 5));
        // Roman-style junk: not a numeral we know, not a digit string.
        assertEquals(Optional.empty(), Levels.parse("iiii", 5));
        // cancel must stay the abandon word, never a level.
        assertEquals(Optional.empty(), Levels.parse("cancel", 5));
    }

    @Test
    void aLongLineIsRejectedWithoutParsingIt() {
        assertEquals(Optional.empty(), Levels.parse("1234567890", 5));
        assertTrue(Levels.parse("999999999", 5).isPresent());
    }
}
