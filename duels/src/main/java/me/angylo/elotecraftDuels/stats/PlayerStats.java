package me.angylo.elotecraftDuels.stats;

/** One player's duel record. Immutable, so it can be read from any thread. */
public record PlayerStats(String name, int wins, int losses, int winStreak, int bestWinStreak, int elo) {

    /** Everyone's rating before their first ranked duel; also the column default in the database. */
    public static final int START_ELO = 1000;
    private static final double ELO_SCALE = 400;

    public static PlayerStats empty(String name) {
        return new PlayerStats(name, 0, 0, 0, 0, START_ELO);
    }

    /**
     * Elo rating the winner of a ranked duel takes from the loser: {@code k * (1 - expected win chance)},
     * so beating a stronger player is worth more. At least 1 so every ranked win counts.
     */
    public static int eloChange(int winnerElo, int loserElo, int kFactor) {
        double expected = 1 / (1 + Math.pow(10, (loserElo - winnerElo) / ELO_SCALE));
        return Math.max(1, (int) Math.round(kFactor * (1 - expected)));
    }

    /** Wins as a whole percentage of finished duels; 0 before the first one. */
    public int winRate() {
        int played = wins + losses;
        return played == 0 ? 0 : Math.round(100f * wins / played);
    }

    PlayerStats win(String newName, int eloChange) {
        int streak = winStreak + 1;
        return new PlayerStats(newName, wins + 1, losses, streak, Math.max(bestWinStreak, streak), elo + eloChange);
    }

    PlayerStats loss(String newName, int eloChange) {
        return new PlayerStats(newName, wins, losses + 1, 0, bestWinStreak, elo - eloChange);
    }
}
