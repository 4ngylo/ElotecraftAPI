package me.angylo.elotecraftDuels.match;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** What an event game ({@link Match.Game}) keeps during one fight. Main thread only. */
final class GameState {

    /** One in the chamber: knockouts each fighter has taken. */
    private final Map<UUID, Integer> knockouts = new HashMap<>();
    /** King of the hill: seconds each team held the hill alone. */
    private final Map<Integer, Integer> points = new HashMap<>();
    /** TNT tag: who carries the TNT, or null between carriers. */
    private UUID tagged;
    private int tagSeconds;

    /** Counts a knockout of {@code fighter}; returns how many they have taken. */
    int knockOut(UUID fighter) {
        return knockouts.merge(fighter, 1, Integer::sum);
    }

    /** Adds a second on the hill for {@code team}; returns its total. */
    int hold(int team) {
        return points.merge(team, 1, Integer::sum);
    }

    int points(int team) {
        return points.getOrDefault(team, 0);
    }

    UUID tagged() {
        return tagged;
    }

    void tag(UUID player) {
        tagged = player;
    }

    int tagSeconds() {
        return tagSeconds;
    }

    void tagSeconds(int seconds) {
        tagSeconds = seconds;
    }
}
