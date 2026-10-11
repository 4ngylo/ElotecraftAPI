package me.angylo.elotecraftDuels.arena;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where one duel runs: an arena in its world. Borrowed from {@link ArenaInstances} for the length of the
 * duel and, for a build duel, until its blocks are back. Main thread only.
 */
public final class ArenaInstance {

    private final Arena arena;
    private final World world;
    private final ArenaChanges changes;
    private final CompletableFuture<Void> ready;
    private boolean closing;

    ArenaInstance(Arena arena, World world, boolean build, CompletableFuture<Void> ready) {
        this.arena = arena;
        this.world = world;
        this.changes = build ? new ArenaChanges() : null;
        this.ready = ready;
    }

    public Arena arena() {
        return arena;
    }

    public World world() {
        return world;
    }

    /** Completes once the arena can be played in: at once, or once a copy is pasted. */
    public CompletableFuture<Void> ready() {
        return ready;
    }

    /** Whether this is a copy pasted for the duel rather than the arena itself. */
    public boolean isCopy() {
        return arena.copy() != null;
    }

    /** Whether fighters may change blocks here (a build kit). */
    public boolean isBuild() {
        return changes != null;
    }

    /** The blocks changed so far; only for a {@link #isBuild() build} duel. */
    public ArenaChanges changes() {
        if (changes == null) {
            throw new IllegalStateException("Not a build duel");
        }
        return changes;
    }

    /** Whether the duel is over and the arena is being put back. */
    public boolean isClosing() {
        return closing;
    }

    void close() {
        closing = true;
    }

    /** @param number 1 or 2 */
    public Location spawn(int number) {
        return arena.spawn(number, world);
    }

    /**
     * Where side {@code index} of {@code sides} starts. Two sides use spawns 1 and 2. More use the arena's
     * extra spawns if there is one for each; otherwise points spread evenly from spawn 1 to spawn 2, facing
     * the middle, and spawn 1 or 2 in turn where such a point has no floor or is blocked.
     */
    public Location spawnFor(int index, int sides) {
        Location fallback = spawn(index % 2 + 1);
        if (sides <= 2) {
            return fallback;
        }
        if (arena.extraSpawns().size() >= sides) {
            return arena.extraSpawns().get(index).in(world);
        }
        Location from = spawn(1);
        Vector line = spawn(2).toVector().subtract(from.toVector());
        Location point = from.clone().add(line.clone().multiply((double) index / (sides - 1)));
        Vector toMiddle = from.toVector().add(line.clone().multiply(0.5)).subtract(point.toVector()).setY(0);
        if (toMiddle.lengthSquared() > 0) {
            point.setDirection(toMiddle);
        }
        point.setPitch(0);
        return standable(point) ? point : fallback;
    }

    /** Spawn 1, spawn 2 or one of the extra spawns, picked at random: where free-for-all fighters come in. */
    public Location randomSpawn() {
        int pick = ThreadLocalRandom.current().nextInt(2 + arena.extraSpawns().size());
        return pick < 2 ? spawn(pick + 1) : arena.extraSpawns().get(pick - 2).in(world);
    }

    /** Inside the arena, with a solid block below and room for a player: no solid block or lava. */
    private boolean standable(Location location) {
        Block feet = location.getBlock();
        return contains(location) && free(feet) && free(feet.getRelative(BlockFace.UP))
                && feet.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    private static boolean free(Block block) {
        return !block.getType().isSolid() && block.getType() != Material.LAVA;
    }

    public Location spectatorSpawn() {
        return arena.spectatorSpawn(world);
    }

    /** The arena's center, else halfway between its spawns. */
    public Location middle() {
        return arena.middle(world);
    }

    public boolean contains(Location location) {
        return world.equals(location.getWorld()) && arena.inBox(location);
    }

    /** Whether fighters may put a block at {@code location}: inside the arena, up to its build limit. */
    public boolean allowsPlacingAt(Location location) {
        return contains(location) && arena.allowsBuildingAt(location.getBlockY());
    }
}
