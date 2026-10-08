package me.angylo.elotecraftDuels.stats;

/** One line of a rating leaderboard: a kit's, or the overall one averaged over kits. */
public record Ranking(String name, int elo, int wins, int losses) {
}
