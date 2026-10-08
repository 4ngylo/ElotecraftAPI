package me.angylo.elotecraftDuels.stats;

/**
 * A player's rating in one kit, with the ranked duels they won and lost in it.
 *
 * @param peak the highest rating this season; a season's end resets it with the rating
 */
public record KitRating(int elo, int wins, int losses, int peak) {

    /** The rating after a ranked duel that moved it by {@code change}: gained if {@code won}, lost otherwise. */
    KitRating after(int change, boolean won) {
        return won ? new KitRating(elo + change, wins + 1, losses, Math.max(peak, elo + change))
                : new KitRating(elo - change, wins, losses + 1, peak);
    }
}
