package me.angylo.elotecraftDuels.arena;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The original state of every block a build duel changed, taken at its first change, so the arena can be
 * put back exactly (container contents and sign text included). One per duel, all in one world.
 * Main thread only.
 */
public final class ArenaChanges {

    private record Position(int x, int y, int z) {
    }

    private final Map<Position, BlockState> originals = new LinkedHashMap<>();
    /** Blocks replaced during the duel, which fighters may break; not the ones that only changed shape. */
    private final Set<Position> replaced = new HashSet<>();

    /** Remembers {@code block} as it is now, before something replaces it, unless it changed earlier in the duel. */
    public void record(Block block) {
        remember(block);
        replaced.add(position(block));
    }

    /** Remembers {@code original}, the state of a block about to be replaced, unless it changed earlier. */
    public void record(BlockState original) {
        Position position = new Position(original.getX(), original.getY(), original.getZ());
        originals.putIfAbsent(position, original);
        replaced.add(position);
    }

    /**
     * Remembers {@code block} as it is now, before it changes shape or state (a fence connecting, a door
     * opening), unless it changed earlier. Unlike {@link #record(Block)} this does not let fighters break it.
     */
    public void remember(Block block) {
        originals.computeIfAbsent(position(block), key -> block.getState());
    }

    /** Whether {@code block} was replaced during the duel, e.g. placed by a fighter. */
    public boolean changed(Block block) {
        return replaced.contains(position(block));
    }

    private static Position position(Block block) {
        return new Position(block.getX(), block.getY(), block.getZ());
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
