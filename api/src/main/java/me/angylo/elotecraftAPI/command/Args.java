package me.angylo.elotecraftAPI.command;

import me.angylo.elotecraftAPI.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Parsing and suggestion helpers for command handlers. Parsers return empty instead of throwing,
 * so a handler can reply with its own message.
 * <pre>{@code
 * .sub("give", null, (sender, args) -> {
 *     Optional<Player> target = Args.player(Args.get(args, 0));
 *     OptionalInt amount = Args.integer(Args.get(args, 1), 1, 64);
 * }, (sender, args) -> args.length == 1 ? Args.players(args) : Args.filter(List.of("1", "16", "64"), args))
 * }</pre>
 */
public final class Args {

    private Args() {
    }

    /** The argument at {@code index}, or {@code ""} if there are fewer arguments. */
    public static String get(String[] args, int index) {
        return index >= 0 && index < args.length ? args[index] : "";
    }

    /** Arguments from {@code from} on, joined with spaces, e.g. a reason or message. */
    public static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, Math.max(0, from), args.length));
    }

    /** An online player by exact name, ignoring case. */
    public static Optional<Player> player(String name) {
        return name.isEmpty() ? Optional.empty() : Optional.ofNullable(Bukkit.getPlayerExact(name));
    }

    /** A whole number from {@code min} to {@code max}. */
    public static OptionalInt integer(String input, int min, int max) {
        try {
            int value = Integer.parseInt(input);
            return value >= min && value <= max ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** A finite decimal number from {@code min} to {@code max}. */
    public static OptionalDouble decimal(String input, double min, double max) {
        try {
            double value = Double.parseDouble(input);
            return Double.isFinite(value) && value >= min && value <= max ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** A duration such as {@code 30s} or {@code 1h30m}; see {@link Durations#parse(String)}. */
    public static Optional<Duration> duration(String input) {
        try {
            return Optional.of(Durations.parse(input));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code options} starting with the last typed argument, ignoring case. */
    public static List<String> filter(Collection<String> options, String[] args) {
        String prefix = get(args, args.length - 1).toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    /** Online player names starting with the last typed argument. */
    public static List<String> players(String[] args) {
        return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args);
    }
}
