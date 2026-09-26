package net.abled.medieval.core.catalogue;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmountsTest {

    @Test
    void parsesAPlainNumber() {
        assertEquals(Optional.of(1), Amounts.parse("1", 64));
        assertEquals(Optional.of(16), Amounts.parse("16", 64));
        assertEquals(Optional.of(64), Amounts.parse("64", 64));
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertEquals(Optional.of(3), Amounts.parse("  3  ", 64));
    }

    @Test
    void clampsToTheStackCeiling() {
        assertEquals(Optional.of(64), Amounts.parse("100", 64));
        assertEquals(Optional.of(16), Amounts.parse("999999", 16));
        assertEquals(Optional.of(1), Amounts.parse("1", 1));
        // A ceiling below one cannot mean "take negative items": it degrades to one.
        assertEquals(Optional.of(1), Amounts.parse("5", 0));
    }

    @Test
    void rejectsZeroAndJunk() {
        assertEquals(Optional.empty(), Amounts.parse("0", 64));
        assertEquals(Optional.empty(), Amounts.parse("", 64));
        assertEquals(Optional.empty(), Amounts.parse("   ", 64));
        assertEquals(Optional.empty(), Amounts.parse(null, 64));
        assertEquals(Optional.empty(), Amounts.parse("abc", 64));
        assertEquals(Optional.empty(), Amounts.parse("1 6", 64));
        assertEquals(Optional.empty(), Amounts.parse("-4", 64));
        assertEquals(Optional.empty(), Amounts.parse("+4", 64));
        assertEquals(Optional.empty(), Amounts.parse("1.5", 64));
        assertEquals(Optional.empty(), Amounts.parse("1,000", 64));
        // cancel must stay the abandon word, never an amount.
        assertEquals(Optional.empty(), Amounts.parse("cancel", 64));
    }

    @Test
    void aLongLineIsRejectedWithoutParsingIt() {
        assertEquals(Optional.empty(), Amounts.parse("1234567890", 64));
        assertTrue(Amounts.parse("999999999", 64).isPresent());
    }
}
