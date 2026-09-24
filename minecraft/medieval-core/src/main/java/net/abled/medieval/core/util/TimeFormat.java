package net.abled.medieval.core.util;

import java.time.Duration;

/** Formats durations for player-facing messages, for example {@code 59m 59s}. */
public final class TimeFormat {

    private TimeFormat() {
    }

    /**
     * Renders a duration using the two largest non-zero units, so "1h 5m" stays readable while
     * short bans keep second precision.
     */
    public static String humanize(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return "0s";
        }

        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append('d');
            if (hours > 0) {
                builder.append(' ').append(hours).append('h');
            }
            return builder.toString();
        }
        if (hours > 0) {
            builder.append(hours).append('h');
            if (minutes > 0) {
                builder.append(' ').append(minutes).append('m');
            }
            return builder.toString();
        }
        if (minutes > 0) {
            builder.append(minutes).append('m');
            if (seconds > 0) {
                builder.append(' ').append(seconds).append('s');
            }
            return builder.toString();
        }
        return seconds + "s";
    }
}
