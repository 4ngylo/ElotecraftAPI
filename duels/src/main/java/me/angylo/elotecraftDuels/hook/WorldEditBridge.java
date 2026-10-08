package me.angylo.elotecraftDuels.hook;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import me.angylo.elotecraftAPI.util.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** {@link WorldEditHook} on the real WorldEdit API. FAWE edits off the main thread, plain WorldEdit on it. */
final class WorldEditBridge implements WorldEditHook {

    private static final String FAWE = "FastAsyncWorldEdit";

    private final Plugin plugin;
    private final boolean async;

    WorldEditBridge(Plugin plugin) {
        this.plugin = plugin;
        this.async = Bukkit.getPluginManager().isPluginEnabled(FAWE);
    }

    private record Blocks(Clipboard clipboard) implements Copy {

        @Override
        public int[] size() {
            BlockVector3 size = clipboard.getDimensions();
            return new int[]{size.x(), size.y(), size.z()};
        }
    }

    @Override
    public String name() {
        return async ? FAWE : "WorldEdit";
    }

    @Override
    public Optional<BoundingBox> selection(Player player) {
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

    @Override
    public CompletableFuture<Copy> copy(World world, BoundingBox box) {
        return run(() -> {
            CuboidRegion region = region(world, box);
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            clipboard.setOrigin(region.getMinimumPoint());
            try (EditSession session = WorldEdit.getInstance().newEditSession(region.getWorld())) {
                ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, region.getMinimumPoint());
                copy.setCopyingEntities(false);
                Operations.complete(copy);
            } catch (WorldEditException e) {
                throw new IllegalStateException("Could not copy the arena", e);
            }
            return new Blocks(clipboard);
        });
    }

    @Override
    public CompletableFuture<Copy> load(Path file) {
        return Tasks.supplyAsync(plugin, () -> {
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
        });
    }

    @Override
    public CompletableFuture<Void> save(Copy copy, Path file) {
        Clipboard clipboard = ((Blocks) copy).clipboard();
        return Tasks.supplyAsync(plugin, () -> {
            try {
                Files.createDirectories(file.getParent());
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                try (OutputStream out = Files.newOutputStream(temporary);
                     ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
                    writer.write(clipboard);
                }
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return null;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write " + file, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> paste(Copy copy, World world, int x, int y, int z) {
        return run(() -> {
            Clipboard clipboard = ((Blocks) copy).clipboard();
            // The origin lands on the paste point, so it is moved for the lowest corner to land on x, y, z.
            BlockVector3 to = BlockVector3.at(x, y, z).add(clipboard.getOrigin()).subtract(clipboard.getRegion().getMinimumPoint());
            try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                Operations.complete(new ClipboardHolder(clipboard).createPaste(session).to(to).ignoreAirBlocks(false).build());
            } catch (WorldEditException e) {
                throw new IllegalStateException("Could not paste the arena", e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> clear(World world, BoundingBox box) {
        return run(() -> {
            CuboidRegion region = region(world, box);
            try (EditSession session = WorldEdit.getInstance().newEditSession(region.getWorld())) {
                // Cast: FAWE regions are also sets of points, which another setBlocks takes.
                session.setBlocks((Region) region, (Pattern) BlockTypes.AIR.getDefaultState());
            } catch (WorldEditException e) {
                throw new IllegalStateException("Could not clear the box", e);
            }
            return null;
        });
    }

    private static CuboidRegion region(World world, BoundingBox box) {
        return new CuboidRegion(BukkitAdapter.adapt(world), BlockVector3.at(box.getMinX(), box.getMinY(), box.getMinZ()),
                BlockVector3.at(box.getMaxX() - 1, box.getMaxY() - 1, box.getMaxZ() - 1));
    }

    /** FAWE edits off the main thread, plain WorldEdit on it, in one go; both complete on the main thread. */
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
}
