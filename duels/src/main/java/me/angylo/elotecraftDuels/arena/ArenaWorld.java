package me.angylo.elotecraftDuels.arena;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Random;
import java.util.logging.Level;

/**
 * The empty void world that {@code /duels arena pregen} pastes arena copies into, away from the lobby. No
 * mobs, weather or day cycle, so every duel there looks the same. Main thread only.
 */
public final class ArenaWorld {

    private static final double SPAWN_Y = 64;

    private ArenaWorld() {
    }

    /** Loads the world {@code name}, creating it empty if missing; empty (logged) if the server could not. */
    public static Optional<World> load(Plugin plugin, String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            try {
                // The generator is given on every load: worlds other than the main ones forget it.
                world = new WorldCreator(name).generator(new Empty()).generateStructures(false).createWorld();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not load or create the arenas world " + name
                        + ", so /duels arena pregen is off", e);
                return Optional.empty();
            }
            if (world == null) {
                plugin.getLogger().warning("Could not load or create the arenas world " + name + ", so /duels arena pregen is off");
                return Optional.empty();
            }
        }
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        return Optional.of(world);
    }

    /** Generates nothing: every chunk is air. */
    private static final class Empty extends ChunkGenerator {

        @Override
        public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
            return new Location(world, 0.5, SPAWN_Y, 0.5);
        }
    }
}
