package me.angylo.elotecraftDuels;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Objects;

/**
 * The highest ping a player takes from queued opponents, picked in {@code /duel options} and kept in their player
 * data like {@link PlayerOptions}. 0 (the default) takes anyone; queues pair two players only if each one's ping
 * is within the other's range.
 */
public final class PingRange {

    /** The choices a click goes through, in milliseconds; 0 is any ping. */
    public static final List<Integer> CHOICES = List.of(0, 50, 100, 150, 200, 300);

    private static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("elotecraftduels:ping-range"));

    private PingRange() {
    }

    /** {@code player}'s range in milliseconds, 0 for any. */
    public static int of(Player player) {
        int range = player.getPersistentDataContainer().getOrDefault(KEY, PersistentDataType.INTEGER, 0);
        return CHOICES.contains(range) ? range : 0;
    }

    /** Moves {@code player} to the next choice, after the last back to any; returns it. */
    public static int next(Player player) {
        int next = CHOICES.get((CHOICES.indexOf(of(player)) + 1) % CHOICES.size());
        player.getPersistentDataContainer().set(KEY, PersistentDataType.INTEGER, next);
        return next;
    }

    /** Whether {@code first} and {@code second} take each other's ping. */
    public static boolean fits(Player first, Player second) {
        return within(of(first), second.getPing()) && within(of(second), first.getPing());
    }

    private static boolean within(int range, int ping) {
        return range == 0 || ping <= range;
    }
}
