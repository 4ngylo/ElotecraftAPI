package me.angylo.elotecraftDuels.stats;

/** One player's duel record. Immutable, so it can be read from any thread. */
public record PlayerStats(String name, int wins, int losses, int winStreak, int bestWinStreak) {

    public static PlayerStats empty(String name) {
        return new PlayerStats(name, 0, 0, 0, 0);
    }

    /** Wins as a whole percentage of finished duels; 0 before the first one. */
    public int winRate() {
        int played = wins + losses;
        return played == 0 ? 0 : Math.round(100f * wins / played);
    }

    PlayerStats win(String newName) {
        int streak = winStreak + 1;
        return new PlayerStats(newName, wins + 1, losses, streak, Math.max(bestWinStreak, streak));
    }

    PlayerStats loss(String newName) {
        return new PlayerStats(newName, wins, losses + 1, 0, bestWinStreak);
    }
}
