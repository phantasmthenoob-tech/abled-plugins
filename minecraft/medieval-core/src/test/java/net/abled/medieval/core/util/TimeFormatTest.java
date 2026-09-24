package net.abled.medieval.core.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {

    @Test
    void formatsSeconds() {
        assertEquals("45s", TimeFormat.humanize(Duration.ofSeconds(45)));
        assertEquals("1s", TimeFormat.humanize(Duration.ofSeconds(1)));
    }

    @Test
    void formatsMinutesAndSeconds() {
        assertEquals("1m", TimeFormat.humanize(Duration.ofMinutes(1)));
        assertEquals("1m 1s", TimeFormat.humanize(Duration.ofSeconds(61)));
        assertEquals("1m 30s", TimeFormat.humanize(Duration.ofSeconds(90)));
    }

    @Test
    void formatsHours() {
        assertEquals("1h", TimeFormat.humanize(Duration.ofHours(1)));
        assertEquals("1h 5m", TimeFormat.humanize(Duration.ofMinutes(65)));
        assertEquals("59m 59s", TimeFormat.humanize(Duration.ofHours(1).minusSeconds(1)));
    }

    @Test
    void formatsDays() {
        assertEquals("1d", TimeFormat.humanize(Duration.ofDays(1)));
        assertEquals("1d 1h", TimeFormat.humanize(Duration.ofHours(25)));
    }

    @Test
    void treatsZeroAndNegativeAsZero() {
        assertEquals("0s", TimeFormat.humanize(Duration.ZERO));
        assertEquals("0s", TimeFormat.humanize(Duration.ofSeconds(-30)));
        assertEquals("0s", TimeFormat.humanize(null));
    }
}
