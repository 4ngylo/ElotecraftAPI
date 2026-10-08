package me.angylo.elotecraftDuels.match;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.Objects;

/**
 * How many ranked duels a player started today (the server's date), for config.yml {@code ranked.daily-limit}. Kept
 * in their player data, so it lasts across restarts but not across servers.
 */
final class DailyRanked {

    static final String UNLIMITED = "duels.queue.ranked.unlimited";

    private static final NamespacedKey DAY = Objects.requireNonNull(NamespacedKey.fromString("elotecraftduels:ranked-day"));
    private static final NamespacedKey COUNT = Objects.requireNonNull(NamespacedKey.fromString("elotecraftduels:ranked-count"));

    private DailyRanked() {
    }

    /** Ranked duels {@code player} started today. */
    static int today(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        long day = data.getOrDefault(DAY, PersistentDataType.LONG, -1L);
        return day == LocalDate.now().toEpochDay() ? data.getOrDefault(COUNT, PersistentDataType.INTEGER, 0) : 0;
    }

    /** Whether {@code player} may start another ranked duel today; {@code limit} 0 is no limit. */
    static boolean allowed(Player player, int limit) {
        return limit == 0 || player.hasPermission(UNLIMITED) || today(player) < limit;
    }

    /** Counts a ranked duel {@code player} just started. */
    static void count(Player player) {
        int played = today(player);
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(DAY, PersistentDataType.LONG, LocalDate.now().toEpochDay());
        data.set(COUNT, PersistentDataType.INTEGER, played + 1);
    }
}
