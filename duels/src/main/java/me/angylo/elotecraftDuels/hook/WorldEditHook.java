package me.angylo.elotecraftDuels.hook;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * FastAsyncWorldEdit or WorldEdit (same API, a required dependency): copies arenas with every block's data
 * (chest contents, sign text, banners), saves and loads schematics, pastes and clears boxes, and reads the
 * player's selection. Futures complete on the main thread. Tests use a fake: MockBukkit runs no WorldEdit.
 */
public interface WorldEditHook {

    /** Blocks copied from a world or a schematic file, ready to paste. */
    interface Copy {
        /** Size in blocks along x, y and z. */
        int[] size();
    }

    /** The real hook; call only once WorldEdit is enabled. */
    static WorldEditHook create(Plugin plugin) {
        return new WorldEditBridge(plugin);
    }

    /** The plugin doing the work, for logs. */
    String name();

    /** {@code player}'s cuboid selection in their world, as a box of whole blocks; empty if none or not a cuboid. */
    Optional<BoundingBox> selection(Player player);

    /** Copies {@code box} of {@code world}. */
    CompletableFuture<Copy> copy(World world, BoundingBox box);

    /** Reads a schematic file. Fails with an {@link java.io.UncheckedIOException} wrapping a {@link java.nio.file.NoSuchFileException} if it is missing. */
    CompletableFuture<Copy> load(Path file);

    /** Writes {@code copy} to a schematic file, replacing it. */
    CompletableFuture<Void> save(Copy copy, Path file);

    /** Pastes {@code copy} with its lowest corner at x, y, z of {@code world}, air included. */
    CompletableFuture<Void> paste(Copy copy, World world, int x, int y, int z);

    /** Sets every block of {@code box} in {@code world} to air. */
    CompletableFuture<Void> clear(World world, BoundingBox box);
}
