package net.abled.medieval.core.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {

    @Test
    void parsesSingleUnits() {
        assertEquals(Duration.ofSeconds(90), DurationParser.parse("90s").orElseThrow());
        assertEquals(Duration.ofMinutes(45), DurationParser.parse("45m").orElseThrow());
        assertEquals(Duration.ofHours(2), DurationParser.parse("2h").orElseThrow());
        assertEquals(Duration.ofDays(2), DurationParser.parse("2d").orElseThrow());
    }

    @Test
    void parsesCombinedUnits() {
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("1h30m").orElseThrow());
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("1h 30m").orElseThrow());
        assertEquals(Duration.ofHours(36), DurationParser.parse("1d12h").orElseThrow());
        assertEquals(Duration.ofSeconds(3661), DurationParser.parse("1h1m1s").orElseThrow());
    }

    @Test
    void treatsBareNumbersAsMinutes() {
        assertEquals(Duration.ofMinutes(30), DurationParser.parse("30").orElseThrow());
        assertEquals(Duration.ofMinutes(1), DurationParser.parse("1").orElseThrow());
    }

    @Test
    void isCaseInsensitiveAndTrimsInput() {
        assertEquals(Duration.ofHours(1), DurationParser.parse("  1H  ").orElseThrow());
    }

    @Test
    void rejectsInvalidInput() {
        assertTrue(DurationParser.parse(null).isEmpty());
        assertTrue(DurationParser.parse("").isEmpty());
        assertTrue(DurationParser.parse("   ").isEmpty());
        assertTrue(DurationParser.parse("banana").isEmpty(), "unknown unit");
        assertTrue(DurationParser.parse("1x").isEmpty(), "unknown unit");
        assertTrue(DurationParser.parse("h").isEmpty(), "missing amount");
        assertTrue(DurationParser.parse("1h30").isEmpty(), "trailing amount without a unit");
        assertTrue(DurationParser.parse("m30").isEmpty(), "leading unit without an amount");
        assertTrue(DurationParser.parse("0").isEmpty(), "a zero ban is meaningless");
        assertTrue(DurationParser.parse("0m").isEmpty(), "a zero ban is meaningless");
        assertTrue(DurationParser.parse("-5m").isEmpty(), "negative durations are not accepted");
        assertTrue(DurationParser.parse("99999999999999999999d").isEmpty(), "overflow must not wrap");
    }

    @Test
    void parseOrThrowExplainsTheAcceptedFormats() {
        assertEquals(Duration.ofHours(1), DurationParser.parseOrThrow("1h"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parseOrThrow("nonsense"));

        assertTrue(failure.getMessage().contains("nonsense"), failure.getMessage());
        assertTrue(failure.getMessage().contains(DurationParser.usage()), failure.getMessage());
    }
}
