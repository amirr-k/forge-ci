package dev.forgeci.core.exec;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the {@code forgeci.yml} duration form: a positive integer plus {@code ms}, {@code s}, {@code m}, or {@code h}. */
public final class Durations {

    private static final Pattern PATTERN = Pattern.compile("^([0-9]+)(ms|s|m|h)$");

    private Durations() {}

    /** Any duration a build task could plausibly want, and small enough that no unit overflows. */
    private static final long MAX_HOURS = 24 * 365;

    public static Duration parse(String value) {
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "invalid duration '" + value + "' (expected a number followed by ms, s, m, or h)");
        }
        String digits = matcher.group(1);
        String unit = matcher.group(2);
        // bound the value before any unit conversion, which would otherwise overflow a long
        long amount = digits.length() > 18 ? Long.MAX_VALUE : Long.parseLong(digits);
        long maximum =
                switch (unit) {
                    case "ms" -> MAX_HOURS * 3_600_000L;
                    case "s" -> MAX_HOURS * 3_600L;
                    case "m" -> MAX_HOURS * 60L;
                    default -> MAX_HOURS;
                };
        if (amount > maximum) {
            throw new IllegalArgumentException(
                    "duration '" + value + "' is too long (the maximum is " + MAX_HOURS + "h)");
        }
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            default -> Duration.ofHours(amount);
        };
    }

    /** Formats a duration for operator-facing output, e.g. {@code 0.4s} or {@code 1m12.5s}. */
    public static String format(Duration duration) {
        long totalMillis = Math.max(duration.toMillis(), 0);
        long minutes = totalMillis / 60_000;
        double seconds = (totalMillis % 60_000) / 1000.0;
        return minutes == 0 ? String.format("%.1fs", seconds) : String.format("%dm%.1fs", minutes, seconds);
    }
}
