package me.angylo.elotecraftDuels.stats;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * One player's duel record. Immutable, so it can be read from any thread.
 *
 * @param legacyElo the one rating of versions before ratings per kit, no longer changed: a kit's rating starts
 *                  from it, so nobody loses progress
 * @param ratings   by kit name, for the kits they played ranked duels with
 */
public record PlayerStats(String name, int wins, int losses, int winStreak, int bestWinStreak, int legacyElo,
                          Map<String, KitRating> ratings) {

    /** Everyone's rating before their first ranked duel; also the column default in the database. */
    public static final int START_ELO = 1000;
    private static final double ELO_SCALE = 400;

    public PlayerStats {
        ratings = Map.copyOf(ratings);
    }

    public static PlayerStats empty(String name) {
        return new PlayerStats(name, 0, 0, 0, 0, START_ELO, Map.of());
    }

    /**
     * Elo rating the winner of a ranked duel takes from the loser: {@code k * (1 - expected win chance)},
     * so beating a stronger player is worth more. At least 1 so every ranked win counts.
     */
    public static int eloChange(int winnerElo, int loserElo, int kFactor) {
        double expected = 1 / (1 + Math.pow(10, (loserElo - winnerElo) / ELO_SCALE));
        return Math.max(1, (int) Math.round(kFactor * (1 - expected)));
    }

    /** The rating in {@code kit}; before their first ranked duel with it, {@link #legacyElo}. */
    public int elo(String kit) {
        KitRating rating = ratings.get(kit);
        return rating == null ? legacyElo : rating.elo();
    }

    /**
     * The overall rating: the average of the ratings in {@code kits} (the kits that exist) they played
     * ranked, rounded like the overall leaderboard; {@link #legacyElo} if none.
     */
    public int overallElo(Collection<String> kits) {
        long sum = 0;
        int count = 0;
        for (Map.Entry<String, KitRating> rating : ratings.entrySet()) {
            if (kits.contains(rating.getKey())) {
                sum += rating.getValue().elo();
                count++;
            }
        }
        return count == 0 ? legacyElo : (int) Math.round((double) sum / count);
    }

    /** The highest rating this season in {@code kit}; before their first ranked duel with it, {@link #legacyElo}. */
    public int peak(String kit) {
        KitRating rating = ratings.get(kit);
        return rating == null ? legacyElo : rating.peak();
    }

    /** The highest peak over {@code kits} they played ranked; {@link #legacyElo} if none. */
    public int peak(Collection<String> kits) {
        return ratings.entrySet().stream().filter(rating -> kits.contains(rating.getKey()))
                .mapToInt(rating -> rating.getValue().peak()).max().orElse(legacyElo);
    }

    /** Wins as a whole percentage of finished duels; 0 before the first one. */
    public int winRate() {
        int played = wins + losses;
        return played == 0 ? 0 : Math.round(100f * wins / played);
    }

    PlayerStats win(String newName) {
        int streak = winStreak + 1;
        return new PlayerStats(newName, wins + 1, losses, streak, Math.max(bestWinStreak, streak), legacyElo, ratings);
    }

    PlayerStats loss(String newName) {
        return new PlayerStats(newName, wins, losses + 1, 0, bestWinStreak, legacyElo, ratings);
    }

    /** After a ranked duel with {@code kit} that moved the rating by {@code change}. */
    PlayerStats rated(String kit, int change, boolean won) {
        Map<String, KitRating> changed = new HashMap<>(ratings);
        changed.put(kit, changed.getOrDefault(kit, new KitRating(legacyElo, 0, 0, legacyElo)).after(change, won));
        return new PlayerStats(name, wins, losses, winStreak, bestWinStreak, legacyElo, changed);
    }
}
