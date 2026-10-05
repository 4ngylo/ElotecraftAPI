package me.angylo.elotecraftDuels.hook;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import me.angylo.elotecraftAPI.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Optional FastAsyncWorldEdit or WorldEdit support (same API): copies arenas with every block's data
 * (chest contents, sign text, banners) for pregen, reads the player's selection and loads schematics.
 * FAWE works off the main thread; plain WorldEdit on it. WorldEdit's classes are only touched in
 * {@link Hook}, created only when one of them is enabled, so the plugin runs without either.
 */
public final class WorldEditHook {

    private static final String API_CLASS = "com.sk89q.worldedit.WorldEdit";
    private static final String FAWE = "FastAsyncWorldEdit";
    private static final String WORLDEDIT = "WorldEdit";

    /** Blocks copied from a world or a schematic file, ready to paste. */
    public interface Copy {
        /** Size in blocks along x, y and z. */
        int[] size();
    }

    private final Plugin plugin;
    private final boolean async;
    private final Hook hook = new Hook();

    private WorldEditHook(Plugin plugin, boolean async) {
        this.plugin = plugin;
        this.async = async;
    }

    /** The hook if FastAsyncWorldEdit or WorldEdit is enabled; logged if it is but cannot be used. */
    public static Optional<WorldEditHook> detect(Plugin plugin) {
        boolean fawe = Bukkit.getPluginManager().isPluginEnabled(FAWE);
        if (!fawe && !Bukkit.getPluginManager().isPluginEnabled(WORLDEDIT)) {
            return Optional.empty();
        }
        try {
            Class.forName(API_CLASS, false, WorldEditHook.class.getClassLoader());
            return Optional.of(new WorldEditHook(plugin, fawe));
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "WorldEdit is installed but could not be used, so arena copies keep blocks only", e);
            return Optional.empty();
        }
    }

    public String name() {
        return async ? FAWE : WORLDEDIT;
    }

    /** {@code player}'s cuboid selection in their world, as a box of whole blocks; empty if none or not a cuboid. */
    public Optional<BoundingBox> selection(Player player) {
        return hook.selection(player);
    }

    /** Copies {@code box} of {@code world}; completes on the main thread. */
    public CompletableFuture<Copy> copy(World world, BoundingBox box) {
        return run(() -> hook.copy(world, box));
    }

    /** Reads a schematic file; completes on the main thread. Fails with an {@link IOException} as the cause. */
    public CompletableFuture<Copy> load(Path file) {
        return Tasks.supplyAsync(plugin, () -> hook.load(file));
    }

    /** Pastes {@code copy} with its lowest corner at x, y, z of {@code world}, air included; completes on the main thread. */
    public CompletableFuture<Void> paste(Copy copy, World world, int x, int y, int z) {
        return run(() -> {
            hook.paste(copy, world, x, y, z);
            return null;
        });
    }

    /** FAWE edits off the main thread, plain WorldEdit on it, in one go. */
    private <T> CompletableFuture<T> run(Supplier<T> work) {
        if (async) {
            return Tasks.supplyAsync(plugin, work);
        }
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Everything that touches WorldEdit's classes. */
    private static final class Hook {

        private record Blocks(Clipboard clipboard) implements Copy {

            @Override
            public int[] size() {
                BlockVector3 size = clipboard.getDimensions();
                return new int[]{size.x(), size.y(), size.z()};
            }
        }

        Optional<BoundingBox> selection(Player player) {
            try {
                Region region = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player))
                        .getSelection(BukkitAdapter.adapt(player.getWorld()));
                if (!(region instanceof CuboidRegion)) {
                    return Optional.empty();
                }
                BlockVector3 min = region.getMinimumPoint();
                BlockVector3 max = region.getMaximumPoint();
                return Optional.of(new BoundingBox(min.x(), min.y(), min.z(), max.x() + 1, max.y() + 1, max.z() + 1));
            } catch (IncompleteRegionException e) {
                return Optional.empty();
            }
        }

        Copy copy(World world, BoundingBox box) {
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            BlockVector3 min = BlockVector3.at(box.getMinX(), box.getMinY(), box.getMinZ());
            BlockVector3 max = BlockVector3.at(box.getMaxX() - 1, box.getMaxY() - 1, box.getMaxZ() - 1);
            CuboidRegion region = new CuboidRegion(weWorld, min, max);
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(min);
            try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
                ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, min);
                copy.setCopyingEntities(false);
                Operations.complete(copy);
            } catch (WorldEditException e) {
                throw new IllegalStateException("Could not copy the arena", e);
            }
            return new Blocks(clipboard);
        }

        Copy load(Path file) {
            if (!Files.isRegularFile(file)) {
                throw new UncheckedIOException(new NoSuchFileException(file.getFileName().toString()));
            }
            ClipboardFormat format = ClipboardFormats.findByFile(file.toFile());
            if (format == null) {
                throw new IllegalStateException(file.getFileName() + " is not a schematic WorldEdit can read");
            }
            try (InputStream in = Files.newInputStream(file); ClipboardReader reader = format.getReader(in)) {
                return new Blocks(reader.read());
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + file.getFileName(), e);
            }
        }

        void paste(Copy copy, World world, int x, int y, int z) {
            Clipboard clipboard = ((Blocks) copy).clipboard();
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            // The origin lands on the paste point, so it is moved for the lowest corner to land on x, y, z.
            BlockVector3 to = BlockVector3.at(x, y, z).add(clipboard.getOrigin()).subtract(clipboard.getRegion().getMinimumPoint());
            try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operations.complete(new ClipboardHolder(clipboard).createPaste(session).to(to).ignoreAirBlocks(false).build());
            } catch (WorldEditException e) {
                throw new IllegalStateException("Could not paste the arena", e);
            }
        }
    }
}
