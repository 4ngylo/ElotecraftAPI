package me.angylo.elotecraftAPI.util;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and formats short human durations such as {@code "1d2h30m"} or {@code "1h 30m"}.
 * Units: {@code d} days, {@code h} hours, {@code m} minutes, {@code s} seconds.
 */
public final class Durations {

    private static final Pattern TOKEN = Pattern.compile("(\\d+)([dhms])");

    private Durations() {
    }

    /**
     * @throws IllegalArgumentException if the input is blank, malformed or overflows
     */
    public static Duration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Duration is empty");
        }
        String compact = input.replace(" ", "").toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN.matcher(compact);
        Duration total = Duration.ZERO;
        int end = 0;
        try {
            while (matcher.find()) {
                if (matcher.start() != end) {
                    break;
                }
                total = total.plus(Long.parseLong(matcher.group(1)), unit(matcher.group(2).charAt(0)));
                end = matcher.end();
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Duration too large: " + input, e);
        }
        if (end == 0 || end != compact.length()) {
            throw new IllegalArgumentException("Invalid duration: " + input);
        }
        return total;
    }

    /** Formats as e.g. {@code "1d 2h 5s"}, dropping zero parts. Zero or negative gives {@code "0s"}. */
    public static String format(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds <= 0) {
            return "0s";
        }
        StringBuilder out = new StringBuilder();
        append(out, seconds / 86_400, 'd');
        append(out, seconds % 86_400 / 3_600, 'h');
        append(out, seconds % 3_600 / 60, 'm');
        append(out, seconds % 60, 's');
        return out.toString();
    }

    private static ChronoUnit unit(char symbol) {
        return switch (symbol) {
            case 'd' -> ChronoUnit.DAYS;
            case 'h' -> ChronoUnit.HOURS;
            case 'm' -> ChronoUnit.MINUTES;
            default -> ChronoUnit.SECONDS;
        };
    }

    private static void append(StringBuilder out, long amount, char symbol) {
        if (amount == 0) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(amount).append(symbol);
    }
}
