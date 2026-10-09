package me.angylo.elotecraftDuels.match;

import me.angylo.elotecraftAPI.hologram.Hologram;
import me.angylo.elotecraftDuels.arena.Arena;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;

/** Bridge: a hologram ({@code match.goal-hologram}) above each goal, from the first round to the end of the fight. */
final class GoalHolograms {

    /** Blocks above the goal point, over a player's head. */
    private static final double HEIGHT = 2.5;

    private GoalHolograms() {
    }

    /** Spawns them once per match, in the world the match runs in. */
    static void show(Plugin plugin, Match match, Component text) {
        if (!match.goalHolograms().isEmpty()) {
            return;
        }
        for (int side = 1; side <= 2; side++) {
            Arena.Position goal = match.arena().points().goal(side);
            if (goal != null) {
                match.goalHolograms().add(Hologram.spawn(plugin, goal.in(match.instance().world()).add(0, HEIGHT, 0), text));
            }
        }
    }

    static void remove(Match match) {
        match.goalHolograms().forEach(Hologram::remove);
        match.goalHolograms().clear();
    }
}
