package net.abled.medieval.core.util;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses short duration strings for administrative commands.
 *
 * <p>Accepted forms: {@code 2h}, {@code 90s}, {@code 1d12h}, {@code 45m}, and combinations with
 * spaces ({@code 1h 30m}). A bare number is read as minutes, which is the friendly admin default.
 */
public final class DurationParser {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([dhms])");
    private static final Pattern BARE_NUMBER = Pattern.compile("\\d+");

    private DurationParser() {
    }

    public static Optional<Duration> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }

        String text = input.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) {
            return Optional.empty();
        }

        if (BARE_NUMBER.matcher(text).matches()) {
            try {
                long minutes = Long.parseLong(text);
                return minutes <= 0 ? Optional.empty() : Optional.of(Duration.ofMinutes(minutes));
            } catch (NumberFormatException overflow) {
                return Optional.empty();
            }
        }

        long seconds = 0L;
        int consumed = 0;
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return Optional.empty();
            }
            try {
                long amount = Long.parseLong(matcher.group(1));
                seconds = Math.addExact(seconds, toSeconds(amount, matcher.group(2)));
            } catch (NumberFormatException | ArithmeticException unusable) {
                return Optional.empty();
            }
            consumed = matcher.end();
        }

        if (consumed != text.length() || seconds <= 0L) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(seconds));
    }

    /** Parses or explains the accepted formats. */
    public static Duration parseOrThrow(String input) {
        return parse(input).orElseThrow(() -> new IllegalArgumentException(
                "invalid duration '" + Objects.toString(input, "") + "'; accepted formats: " + usage()));
    }

    public static String usage() {
        return "30m, 2h, 1h30m, 90s, 2d";
    }

    private static long toSeconds(long amount, String unit) {
        return switch (unit) {
            case "d" -> Math.multiplyExact(amount, 86_400L);
            case "h" -> Math.multiplyExact(amount, 3_600L);
            case "m" -> Math.multiplyExact(amount, 60L);
            default -> amount;
        };
    }
}
