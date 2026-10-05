package me.angylo.elotecraftDuels.arena;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Where one duel runs: an arena in its world. Borrowed from {@link ArenaInstances} for the length of the
 * duel and, for a build duel, until its blocks are back. Main thread only.
 */
public final class ArenaInstance {

    private final Arena arena;
    private final World world;
    private final ArenaChanges changes;
    private boolean closing;

    ArenaInstance(Arena arena, World world, boolean build) {
        this.arena = arena;
        this.world = world;
        this.changes = build ? new ArenaChanges() : null;
    }

    public Arena arena() {
        return arena;
    }

    public World world() {
        return world;
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

    public Location spectatorSpawn() {
        return arena.spectatorSpawn(world);
    }

    public boolean contains(Location location) {
        return world.equals(location.getWorld()) && arena.inBox(location);
    }
}
