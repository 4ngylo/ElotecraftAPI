package me.angylo.elotecraftDuels.arena;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The original state of every block a build duel changed, taken at its first change, so the arena can be
 * put back exactly (container contents and sign text included). One per duel, all in one world.
 * Main thread only.
 */
public final class ArenaChanges {

    private record Position(int x, int y, int z) {
    }

    private final Map<Position, BlockState> originals = new LinkedHashMap<>();

    /** Remembers {@code block} as it is now, unless it already changed earlier in the duel. */
    public void record(Block block) {
        originals.computeIfAbsent(new Position(block.getX(), block.getY(), block.getZ()), key -> block.getState());
    }

    /** Remembers {@code original}, the state of a block about to change, unless it already changed earlier. */
    public void record(BlockState original) {
        originals.putIfAbsent(new Position(original.getX(), original.getY(), original.getZ()), original);
    }

    /** Whether {@code block} changed during the duel, e.g. was placed by a fighter. */
    public boolean changed(Block block) {
        return originals.containsKey(new Position(block.getX(), block.getY(), block.getZ()));
    }

    public int size() {
        return originals.size();
    }

    /**
     * Puts back up to {@code budget} blocks, without physics so nothing pops off or flows meanwhile.
     *
     * @return whether every block is back
     */
    boolean restore(int budget) {
        Iterator<BlockState> pending = originals.values().iterator();
        for (int done = 0; done < budget && pending.hasNext(); done++) {
            BlockState original = pending.next();
            // Removed first: a block that fails to update is not retried forever.
            pending.remove();
            original.update(true, false);
        }
        return originals.isEmpty();
    }
}
