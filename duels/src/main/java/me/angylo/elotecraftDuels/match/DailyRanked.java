package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftDuels.PermissionLimits;
import me.angylo.elotecraftDuels.Settings;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.Objects;

/**
 * How many ranked duels a player started today (the server's date), for config.yml {@code ranked.daily-limit}, raised
 * by {@code duels.queue.ranked.limit.<n>} and lifted by {@code duels.queue.ranked.unlimited}. Kept
 * in their player data, so it lasts across restarts but not across servers.
 */
public final class DailyRanked {

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

    /** {@code player}'s daily limit: {@code configured} raised by their permission; 0 (no limit) stays 0. */
    static int limit(Player player, int configured) {
        return configured == 0 ? 0 : PermissionLimits.highest(player, PermissionLimits.RANKED_DAILY, configured, Settings.MAX_DAILY_RANKED);
    }

    /** Whether {@code player} may start another ranked duel today; {@code limit} 0 is no limit. */
    static boolean allowed(Player player, int limit) {
        return limit == 0 || player.hasPermission(UNLIMITED) || today(player) < limit;
    }

    /** Counts a ranked duel {@code player} just started. */
    public static void count(Player player) {
        int played = today(player);
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(DAY, PersistentDataType.LONG, LocalDate.now().toEpochDay());
        data.set(COUNT, PersistentDataType.INTEGER, played + 1);
    }
}
